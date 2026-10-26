package sqlservice

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"strings"

	goora "github.com/sijms/go-ora/v2"
)

type MetadataSection struct {
	Deferred  bool             `json:"deferred,omitempty"`
	Supported bool             `json:"supported"`
	Items     []map[string]any `json:"items,omitempty"`
	Text      string           `json:"text,omitempty"`
	Source    string           `json:"source,omitempty"`
	Error     string           `json:"error,omitempty"`
	Truncated bool             `json:"truncated,omitempty"`
}
type TableInspection struct {
	Database  string                     `json:"database"`
	Schema    string                     `json:"schema"`
	Table     string                     `json:"table"`
	Columns   []CatalogColumn            `json:"columns"`
	Sections  map[string]MetadataSection `json:"sections"`
	Truncated bool                       `json:"truncated"`
}

// InspectTable only reads system catalogs visible to the authenticated account.
// Each section declares support separately. Missing privileges never turn a
// partial/synthetic definition into a native DDL claim.
func InspectTable(ctx context.Context, conn *sql.Conn, d Dialect, req CatalogColumnRequest) (TableInspection, error) {
	return inspectTable(ctx, conn, d, req, false)
}
func InspectTableSummary(ctx context.Context, conn *sql.Conn, d Dialect, req CatalogColumnRequest) (TableInspection, error) {
	return inspectTable(ctx, conn, d, req, false)
}
func inspectTable(ctx context.Context, conn *sql.Conn, d Dialect, req CatalogColumnRequest, includeDDL bool) (TableInspection, error) {
	result := TableInspection{Database: req.Database, Schema: req.Schema, Table: req.Table, Sections: map[string]MetadataSection{}}
	req.Limit = 200
	req.AfterOrdinal = 0
	columns, err := ColumnRows(ctx, conn, d, req)
	if err != nil {
		return result, err
	}
	if len(columns) > 200 {
		columns = columns[:200]
		result.Truncated = true
	}
	result.Columns = columns
	if len(columns) == 0 {
		return result, ErrCatalogNotFound
	}
	ddl := MetadataSection{Supported: ddlSupported(d), Source: ddlSource(d), Deferred: true}
	if d == PostgreSQL {
		var fullAccess bool
		var relationKind string
		err := conn.QueryRowContext(ctx, `SELECT
pg_catalog.has_table_privilege(t.oid, 'SELECT,INSERT,UPDATE,REFERENCES'), t.relkind::text
FROM pg_catalog.pg_class t JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace
WHERE n.nspname=$1 AND t.relname=$2`, req.Schema, req.Table).Scan(&fullAccess, &relationKind)
		if err != nil {
			return result, fmt.Errorf("check definition visibility: %w", err)
		}
		if !fullAccess {
			ddl = MetadataSection{Supported: false, Source: "restricted", Error: "DDL requires table-level column access"}
		} else if relationKind == "v" || relationKind == "m" {
			ddl.Source = "native_view_query"
		} else if result.Truncated {
			ddl = MetadataSection{Supported: false, Source: "reconstructed_columns", Truncated: true, Error: "Column preview exceeds the 200-column display limit"}
		}
	}
	result.Sections["ddl"] = ddl
	for _, kind := range []string{"primary_key", "foreign_keys", "indexes", "defaults"} {
		q, names, ok := inspectionQuery(d, req, kind)
		if !ok {
			result.Sections[kind] = MetadataSection{Supported: false}
			continue
		}
		section := MetadataSection{Supported: true, Items: []map[string]any{}}
		rows, err := conn.QueryContext(ctx, q.SQL, q.Args...)
		if err != nil {
			section.Error = "Metadata is unavailable for this account or server version"
			result.Sections[kind] = section
			continue
		}
		for rows.Next() {
			vals := make([]any, len(names))
			dest := make([]any, len(names))
			for i := range vals {
				dest[i] = &vals[i]
			}
			if err = rows.Scan(dest...); err != nil {
				break
			}
			if len(section.Items) >= 200 {
				section.Truncated = true
				break
			}
			item := map[string]any{}
			for i, n := range names {
				switch value := vals[i].(type) {
				case []byte:
					item[n] = string(value)
				default:
					item[n] = value
				}
			}
			section.Items = append(section.Items, item)
		}
		if err == nil {
			err = rows.Err()
		}
		_ = rows.Close()
		if err != nil {
			section.Error = "Unable to read complete metadata"
		}
		result.Sections[kind] = section
	}
	if includeDDL && ddl.Supported {
		result.Sections["ddl"] = inspectionDDL(ctx, conn, d, req, result)
	}
	return result, ctx.Err()
}
func inspectionQuery(d Dialect, r CatalogColumnRequest, kind string) (catalogQuery, []string, bool) {
	b := catalogBinder{dialect: d}
	a, c := b.bind(r.Schema), b.bind(r.Table)
	if d == PostgreSQL {
		a, c = "$1", "$2"
	}
	q := ""
	names := []string{}
	switch kind {
	case "defaults":
		names = []string{"column", "expression"}
		switch {
		case IsMySQLDialect(d):
			q = `SELECT COLUMN_NAME, COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=` + a + ` AND TABLE_NAME=` + c + ` ORDER BY ORDINAL_POSITION`
		case d == PostgreSQL:
			names = []string{"column", "expression", "generated", "identity"}
			q = `SELECT a.attname, pg_catalog.pg_get_expr(ad.adbin,ad.adrelid), COALESCE(to_jsonb(a)->>'attgenerated',''), COALESCE(to_jsonb(a)->>'attidentity','') FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class t ON t.oid=a.attrelid JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace LEFT JOIN pg_catalog.pg_attrdef ad ON ad.adrelid=t.oid AND ad.adnum=a.attnum WHERE n.nspname=` + a + ` AND t.relname=` + c + ` AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum`
		case d == SQLServer:
			q = `SELECT col.name, dc.definition FROM sys.columns col JOIN sys.objects t ON t.object_id=col.object_id JOIN sys.schemas s ON s.schema_id=t.schema_id LEFT JOIN sys.default_constraints dc ON dc.object_id=col.default_object_id WHERE s.name=` + a + ` AND t.name=` + c + ` ORDER BY col.column_id`
		case IsOracleDialect(d):
			q = `SELECT COLUMN_NAME, DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER=` + a + ` AND TABLE_NAME=` + c + ` ORDER BY COLUMN_ID`
		case d == DB2:
			q = `SELECT COLNAME, DEFAULT FROM SYSCAT.COLUMNS WHERE TABSCHEMA=` + a + ` AND TABNAME=` + c + ` ORDER BY COLNO`
		case d == ClickHouse:
			q = `SELECT name, default_expression FROM system.columns WHERE database=` + a + ` AND table=` + c + ` ORDER BY position`
		}
	case "primary_key":
		names = []string{"name", "column", "ordinal"}
		switch {
		case IsMySQLDialect(d):
			q = `SELECT CONSTRAINT_NAME, COLUMN_NAME, ORDINAL_POSITION FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=` + a + ` AND TABLE_NAME=` + c + ` AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION`
		case d == PostgreSQL:
			q = `SELECT k.conname,a.attname,u.ord FROM pg_catalog.pg_constraint k JOIN pg_catalog.pg_class t ON t.oid=k.conrelid JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace CROSS JOIN LATERAL unnest(k.conkey) WITH ORDINALITY AS u(attnum,ord) JOIN pg_catalog.pg_attribute a ON a.attrelid=t.oid AND a.attnum=u.attnum WHERE n.nspname=` + a + ` AND t.relname=` + c + ` AND k.contype='p' ORDER BY u.ord`
		case d == SQLServer:
			q = `SELECT i.name,c.name,ic.key_ordinal FROM sys.indexes i JOIN sys.tables t ON t.object_id=i.object_id JOIN sys.schemas s ON s.schema_id=t.schema_id JOIN sys.index_columns ic ON ic.object_id=t.object_id AND ic.index_id=i.index_id JOIN sys.columns c ON c.object_id=t.object_id AND c.column_id=ic.column_id WHERE s.name=` + a + ` AND t.name=` + c + ` AND i.is_primary_key=1 ORDER BY ic.key_ordinal`
		case IsOracleDialect(d):
			q = `SELECT c.CONSTRAINT_NAME,k.COLUMN_NAME,k.POSITION FROM ALL_CONSTRAINTS c JOIN ALL_CONS_COLUMNS k ON k.OWNER=c.OWNER AND k.CONSTRAINT_NAME=c.CONSTRAINT_NAME WHERE c.OWNER=` + a + ` AND c.TABLE_NAME=` + c + ` AND c.CONSTRAINT_TYPE='P' ORDER BY k.POSITION`
		case d == DB2:
			q = `SELECT k.CONSTNAME,k.COLNAME,k.COLSEQ FROM SYSCAT.KEYCOLUSE k JOIN SYSCAT.TABCONST t ON t.TABSCHEMA=k.TABSCHEMA AND t.TABNAME=k.TABNAME AND t.CONSTNAME=k.CONSTNAME WHERE k.TABSCHEMA=` + a + ` AND k.TABNAME=` + c + ` AND t.TYPE='P' ORDER BY k.COLSEQ`
		case d == ClickHouse:
			q = `SELECT 'PRIMARY', name, position FROM system.columns WHERE database=` + a + ` AND table=` + c + ` AND is_in_primary_key=1 ORDER BY position`
		}
	case "indexes":
		names = []string{"name", "column", "ordinal", "unique"}
		switch {
		case IsMySQLDialect(d):
			q = `SELECT INDEX_NAME,COLUMN_NAME,SEQ_IN_INDEX,CASE WHEN NON_UNIQUE=0 THEN 1 ELSE 0 END FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=` + a + ` AND TABLE_NAME=` + c + ` ORDER BY INDEX_NAME,SEQ_IN_INDEX`
		case d == PostgreSQL:
			q = `SELECT ci.relname,pg_catalog.pg_get_indexdef(i.indexrelid,u.ord::integer,true),u.ord,CASE WHEN i.indisunique THEN 1 ELSE 0 END FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class t ON t.oid=i.indrelid JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace JOIN pg_catalog.pg_class ci ON ci.oid=i.indexrelid CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY u(attnum,ord) WHERE n.nspname=` + a + ` AND t.relname=` + c + ` ORDER BY ci.relname,u.ord`
		case d == SQLServer:
			q = `SELECT i.name,c.name,ic.key_ordinal,CONVERT(int,i.is_unique) FROM sys.indexes i JOIN sys.objects t ON t.object_id=i.object_id JOIN sys.schemas s ON s.schema_id=t.schema_id JOIN sys.index_columns ic ON ic.object_id=t.object_id AND ic.index_id=i.index_id JOIN sys.columns c ON c.object_id=t.object_id AND c.column_id=ic.column_id WHERE s.name=` + a + ` AND t.name=` + c + ` AND i.index_id>0 ORDER BY i.name,ic.index_column_id`
		case IsOracleDialect(d):
			q = `SELECT i.INDEX_NAME,k.COLUMN_NAME,k.COLUMN_POSITION,CASE WHEN i.UNIQUENESS='UNIQUE' THEN 1 ELSE 0 END FROM ALL_INDEXES i JOIN ALL_IND_COLUMNS k ON k.INDEX_OWNER=i.OWNER AND k.INDEX_NAME=i.INDEX_NAME WHERE i.TABLE_OWNER=` + a + ` AND i.TABLE_NAME=` + c + ` ORDER BY i.INDEX_NAME,k.COLUMN_POSITION`
		case d == DB2:
			q = `SELECT i.INDNAME,k.COLNAME,k.COLSEQ,CASE WHEN i.UNIQUERULE IN ('P','U') THEN 1 ELSE 0 END FROM SYSCAT.INDEXES i JOIN SYSCAT.INDEXCOLUSE k ON k.INDSCHEMA=i.INDSCHEMA AND k.INDNAME=i.INDNAME WHERE i.TABSCHEMA=` + a + ` AND i.TABNAME=` + c + ` ORDER BY i.INDNAME,k.COLSEQ`
		case d == ClickHouse:
			q = `SELECT name,expr,0,0 FROM system.data_skipping_indices WHERE database=` + a + ` AND table=` + c + ` ORDER BY name`
		}
	case "foreign_keys":
		names = []string{"name", "column", "referenced_schema", "referenced_table", "referenced_column", "ordinal"}
		switch {
		case IsMySQLDialect(d):
			q = `SELECT CONSTRAINT_NAME,COLUMN_NAME,REFERENCED_TABLE_SCHEMA,REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME,ORDINAL_POSITION FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=` + a + ` AND TABLE_NAME=` + c + ` AND REFERENCED_TABLE_NAME IS NOT NULL ORDER BY CONSTRAINT_NAME,ORDINAL_POSITION`
		case d == PostgreSQL:
			q = `SELECT k.conname,ac.attname,nr.nspname,tr.relname,ar.attname,u.ord FROM pg_catalog.pg_constraint k JOIN pg_catalog.pg_class t ON t.oid=k.conrelid JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace JOIN pg_catalog.pg_class tr ON tr.oid=k.confrelid JOIN pg_catalog.pg_namespace nr ON nr.oid=tr.relnamespace CROSS JOIN LATERAL unnest(k.conkey,k.confkey) WITH ORDINALITY AS u(attnum,refnum,ord) JOIN pg_catalog.pg_attribute ac ON ac.attrelid=t.oid AND ac.attnum=u.attnum JOIN pg_catalog.pg_attribute ar ON ar.attrelid=tr.oid AND ar.attnum=u.refnum WHERE n.nspname=` + a + ` AND t.relname=` + c + ` AND k.contype='f' ORDER BY k.conname,u.ord`
		case d == SQLServer:
			q = `SELECT fk.name,c.name,rs.name,rt.name,rc.name,k.constraint_column_id FROM sys.foreign_keys fk JOIN sys.foreign_key_columns k ON k.constraint_object_id=fk.object_id JOIN sys.tables t ON t.object_id=k.parent_object_id JOIN sys.schemas s ON s.schema_id=t.schema_id JOIN sys.columns c ON c.object_id=t.object_id AND c.column_id=k.parent_column_id JOIN sys.tables rt ON rt.object_id=k.referenced_object_id JOIN sys.schemas rs ON rs.schema_id=rt.schema_id JOIN sys.columns rc ON rc.object_id=rt.object_id AND rc.column_id=k.referenced_column_id WHERE s.name=` + a + ` AND t.name=` + c + ` ORDER BY fk.name,k.constraint_column_id`
		case IsOracleDialect(d):
			q = `SELECT f.CONSTRAINT_NAME,k.COLUMN_NAME,r.OWNER,r.TABLE_NAME,rk.COLUMN_NAME,k.POSITION FROM ALL_CONSTRAINTS f JOIN ALL_CONS_COLUMNS k ON k.OWNER=f.OWNER AND k.CONSTRAINT_NAME=f.CONSTRAINT_NAME JOIN ALL_CONSTRAINTS r ON r.OWNER=f.R_OWNER AND r.CONSTRAINT_NAME=f.R_CONSTRAINT_NAME JOIN ALL_CONS_COLUMNS rk ON rk.OWNER=r.OWNER AND rk.CONSTRAINT_NAME=r.CONSTRAINT_NAME AND rk.POSITION=k.POSITION WHERE f.OWNER=` + a + ` AND f.TABLE_NAME=` + c + ` AND f.CONSTRAINT_TYPE='R' ORDER BY f.CONSTRAINT_NAME,k.POSITION`
		case d == DB2:
			q = `SELECT f.CONSTNAME,k.COLNAME,f.REFTABSCHEMA,f.REFTABNAME,rk.COLNAME,k.COLSEQ FROM SYSCAT.REFERENCES f JOIN SYSCAT.KEYCOLUSE k ON k.TABSCHEMA=f.TABSCHEMA AND k.TABNAME=f.TABNAME AND k.CONSTNAME=f.CONSTNAME JOIN SYSCAT.KEYCOLUSE rk ON rk.TABSCHEMA=f.REFTABSCHEMA AND rk.TABNAME=f.REFTABNAME AND rk.CONSTNAME=f.REFKEYNAME AND rk.COLSEQ=k.COLSEQ WHERE f.TABSCHEMA=` + a + ` AND f.TABNAME=` + c + ` ORDER BY f.CONSTNAME,k.COLSEQ`
		}
	}
	if q == "" {
		return catalogQuery{}, nil, false
	}
	if d == PostgreSQL {
		// PostgreSQL catalogs are broadly readable. The workspace still follows
		// the column scope used by ColumnRows, including composite metadata and
		// generated/default expressions that depend on inaccessible columns.
		guard := postgresInspectionGuard(kind)
		if guard != "" {
			if pos := strings.LastIndex(q, " ORDER BY "); pos >= 0 {
				q = q[:pos] + " AND (" + guard + ")" + q[pos:]
			}
		}
	}
	return catalogQuery{q, b.args}, names, true
}

const postgresColumnPrivileges = "'SELECT,INSERT,UPDATE,REFERENCES'"

func postgresInspectionGuard(kind string) string {
	full := "pg_catalog.has_table_privilege(t.oid, " + postgresColumnPrivileges + ")"
	sourceKeys := "NOT EXISTS (SELECT 1 FROM unnest(k.conkey) scoped(attnum) WHERE NOT pg_catalog.has_column_privilege(t.oid, scoped.attnum, " + postgresColumnPrivileges + "))"
	switch kind {
	case "defaults":
		return "pg_catalog.has_column_privilege(t.oid, a.attnum, " + postgresColumnPrivileges + ") AND (" + full + ` OR NOT EXISTS (
SELECT 1 FROM pg_catalog.pg_depend dep
WHERE dep.classid='pg_catalog.pg_attrdef'::regclass AND dep.objid=ad.oid
AND dep.refclassid='pg_catalog.pg_class'::regclass AND dep.refobjid=t.oid AND dep.refobjsubid>0
AND NOT pg_catalog.has_column_privilege(t.oid, dep.refobjsubid::smallint, ` + postgresColumnPrivileges + ")))"
	case "primary_key":
		return sourceKeys
	case "foreign_keys":
		return sourceKeys + " AND pg_catalog.has_schema_privilege(nr.oid, 'USAGE') AND NOT EXISTS (SELECT 1 FROM unnest(k.confkey) scoped(attnum) WHERE NOT pg_catalog.has_column_privilege(tr.oid, scoped.attnum, " + postgresColumnPrivileges + "))"
	case "indexes":
		// A rendered expression or partial-index predicate cannot safely be
		// inferred from its label. Hide the complete index for a column grant.
		return full + " OR (i.indexprs IS NULL AND i.indpred IS NULL AND NOT EXISTS (SELECT 1 FROM unnest(i.indkey) scoped(attnum) WHERE scoped.attnum<=0 OR NOT pg_catalog.has_column_privilege(t.oid, scoped.attnum, " + postgresColumnPrivileges + ")))"
	}
	return ""
}

func inspectionDDL(ctx context.Context, conn *sql.Conn, d Dialect, r CatalogColumnRequest, info TableInspection) MetadataSection {
	section := MetadataSection{Supported: true, Source: "native"}
	q := ""
	args := []any{}
	switch {
	case IsMySQLDialect(d), d == ClickHouse:
		quote := byte('`')
		q = "SHOW CREATE TABLE " + quoteIdentifier(r.Schema, d, quote) + "." + quoteIdentifier(r.Table, d, quote)
		rows, err := conn.QueryContext(ctx, q)
		if err != nil {
			section.Error = "DDL is unavailable for this account or object"
			return section
		}
		defer rows.Close()
		names, _ := rows.Columns()
		vals := make([]any, len(names))
		ptrs := make([]any, len(vals))
		for i := range vals {
			ptrs[i] = &vals[i]
		}
		if rows.Next() && rows.Scan(ptrs...) == nil {
			index := 1
			if d == ClickHouse {
				index = 0
			}
			if len(vals) > index {
				switch value := vals[index].(type) {
				case string:
					section.Text = value
				case []byte:
					section.Text = string(value)
				}
			}
		}
		if section.Text == "" {
			section.Error = "DDL is unavailable for this object"
		}
		return boundedDDL(section)
	case d == Oracle:
		var views int
		if err := conn.QueryRowContext(ctx, "SELECT COUNT(*) FROM ALL_VIEWS WHERE OWNER=:1 AND VIEW_NAME=:2", r.Schema, r.Table).Scan(&views); err != nil {
			section.Error = "DDL object type is unavailable"
			return section
		}
		kind := "TABLE"
		if views > 0 {
			kind = "VIEW"
		}
		q = "SELECT DBMS_METADATA.GET_DDL(:1,:2,:3) FROM DUAL"
		args = []any{kind, r.Table, r.Schema}
	case d == Dameng:
		q = `SELECT TABLEDEF(?,?) FROM DUAL`
		args = []any{r.Schema, r.Table}
	case d == PostgreSQL:
		if info.Sections["ddl"].Source == "native_view_query" {
			section.Source = "native_view_query"
			q = `SELECT pg_catalog.pg_get_viewdef(t.oid,true)
FROM pg_catalog.pg_class t JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace
WHERE n.nspname=$1 AND t.relname=$2 AND t.relkind IN ('v','m')
AND pg_catalog.has_table_privilege(t.oid, 'SELECT,INSERT,UPDATE,REFERENCES')`
			args = []any{r.Schema, r.Table}
			break
		}
		// PostgreSQL has no built-in CREATE TABLE serializer. This explicit,
		// conservative reconstruction omits storage/partition/identity details.
		section.Source = "reconstructed_columns"
		lines := []string{}
		defaults := map[string]string{}
		for _, row := range info.Sections["defaults"].Items {
			if val, ok := row["expression"].(string); ok && row["generated"] == "" && row["identity"] == "" {
				defaults[fmt.Sprint(row["column"])] = val
			}
		}
		for _, col := range info.Columns {
			line := quoteIdentifier(col.Name, d, '"') + " " + col.DataType
			if val := defaults[col.Name]; val != "" {
				line += " DEFAULT " + val
			}
			if !col.Nullable {
				line += " NOT NULL"
			}
			lines = append(lines, "  "+line)
		}
		section.Text = "-- Column definition preview; constraints, indexes, identity/generated-column, storage and partition options are not included.\nCREATE TABLE " + quoteIdentifier(r.Schema, d, '"') + "." + quoteIdentifier(r.Table, d, '"') + " (\n" + strings.Join(lines, ",\n") + "\n);"
		return boundedDDL(section)
	default:
		return MetadataSection{Supported: false}
	}
	rows, err := conn.QueryContext(ctx, q, args...)
	if err != nil {
		section.Error = "DDL is unavailable for this account or object"
		return section
	}
	defer rows.Close()
	for rows.Next() {
		var value any
		if err := rows.Scan(&value); err != nil {
			section.Error = "Unable to read DDL"
			break
		}
		text, err := metadataText(value)
		if err != nil {
			section.Error = "DDL exceeds the display limit or cannot be decoded"
			break
		}
		section.Text += text
		if len(section.Text) > 128<<10 {
			section.Text = ""
			section.Truncated = true
			section.Error = "DDL exceeds the 128 KiB display limit"
			break
		}
	}
	if rows.Err() != nil {
		section.Text = ""
		section.Error = "Unable to read complete DDL"
	}
	if section.Source == "native_view_query" && section.Text != "" {
		section.Text = "-- Native view SELECT definition; this is not a CREATE statement.\n" + section.Text
	}
	return boundedDDL(section)
}
func boundedDDL(s MetadataSection) MetadataSection {
	if len(s.Text) > 128<<10 {
		s.Text = ""
		s.Error = "DDL exceeds the 128 KiB display limit"
		s.Truncated = true
	}
	return s
}

// Driver LOB locators must be consumed while their connection is still pinned.
func metadataText(value any) (string, error) {
	switch x := value.(type) {
	case nil:
		return "", nil
	case string:
		return x, nil
	case []byte:
		return string(x), nil
	case goora.Clob:
		return x.String, nil
	case goora.NClob:
		return x.String, nil
	case interface {
		GetLength() (int64, error)
		ReadString(int, int) (string, error)
	}:
		n, err := x.GetLength()
		if err != nil {
			return "", err
		}
		if n > 128<<10 || n < 0 {
			return "", errors.New("DDL too large")
		}
		if n == 0 {
			return "", nil
		}
		text, err := x.ReadString(1, int(n))
		if errors.Is(err, io.EOF) {
			err = nil
		}
		return text, err
	default:
		return "", errors.New("Unsupported DDL value")
	}
}

func ddlSupported(d Dialect) bool {
	return IsMySQLDialect(d) || d == ClickHouse || d == Oracle || d == Dameng || d == PostgreSQL
}
func ddlSource(d Dialect) string {
	if d == PostgreSQL {
		return "reconstructed_columns"
	}
	if ddlSupported(d) {
		return "native"
	}
	return ""
}
func TableDefinition(ctx context.Context, conn *sql.Conn, d Dialect, req CatalogColumnRequest) (MetadataSection, error) {
	info, err := inspectTable(ctx, conn, d, req, true)
	if err != nil {
		return MetadataSection{}, err
	}
	return info.Sections["ddl"], nil
}
