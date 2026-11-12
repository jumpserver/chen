package sqlservice

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"sort"
	"strings"
)

// LoadBrowseTable needs authorized read metadata, not identity/default flags
// required by editing. A successful read never grants grid write permission.
func LoadBrowseTable(ctx context.Context, conn *sql.Conn, d Dialect, scope Context, schema, table string) (EditTable, error) {
	out := EditTable{Database: scope.Database, Schema: schema, Table: table}
	if !supported(d) {
		return out, ErrUnsupportedDialect
	}
	if err := validateCatalogInput(conn, d, scope.Database, schema, table); err != nil {
		return out, err
	}
	if table == "" {
		return out, ErrCatalogNotFound
	}
	ctx, cancel := MetadataContext(ctx, d)
	defer cancel()
	if err := catalogDatabaseAccess(ctx, conn, d, scope.Database); err != nil {
		return out, err
	}
	if IsMySQLDialect(d) || d == ClickHouse {
		if schema == "" {
			schema = scope.Database
		}
		if schema != scope.Database {
			return out, ErrCatalogForbidden
		}
	} else if schema == "" {
		if d == PostgreSQL {
			if err := conn.QueryRowContext(ctx, `SELECT n.nspname FROM pg_catalog.pg_class t JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace WHERE t.oid=pg_catalog.to_regclass($1)`, quoteIdentifier(table, d, '"')).Scan(&schema); err != nil {
				return out, err
			}
		} else if d == SQLServer {
			if err := conn.QueryRowContext(ctx, `SELECT OBJECT_SCHEMA_NAME(OBJECT_ID(@p1))`, quoteIdentifier(table, d, '[')).Scan(&schema); err != nil {
				return out, err
			}
		} else {
			var err error
			schema, err = extraDefaultSchema(ctx, conn, d, scope.Database)
			if err != nil {
				return out, err
			}
		}
	}
	if !IsMySQLDialect(d) && systemCatalogSchema(schema, d) {
		return out, ErrCatalogForbidden
	}
	if err := catalogObjectAccess(ctx, conn, d, schema, table); err != nil {
		if !IsMySQLDialect(d) || !errors.Is(err, ErrCatalogNotFound) {
			return out, err
		}
		var err error
		table, err = editMySQLCanonicalTable(ctx, conn, schema, table)
		if err != nil {
			return out, err
		}
	}
	out.Schema, out.Table = schema, table
	r := CatalogColumnRequest{Database: scope.Database, Schema: schema, Table: table, Limit: MaxCatalogPageSize}
	// View reads require explicit reexecution confirmation at the HTTP layer.
	isView, err := BrowseRelationIsView(ctx, conn, d, r)
	if err != nil {
		return out, err
	}
	out.View = isView
	cols, err := ColumnRows(ctx, conn, d, r)
	if err != nil {
		return out, err
	}
	if len(cols) == 0 || len(cols) > MaxCatalogPageSize {
		return out, fmt.Errorf("complete browse column metadata exceeds its limit")
	}
	for _, c := range cols {
		out.Columns = append(out.Columns, EditColumn{Name: c.Name, DataType: c.DataType, Nullable: c.Nullable, Supported: EditTypeSupported(d, c.DataType)})
	}
	// Only complete non-null, unconditional unique keys promise a tie breaker.
	// ClickHouse primary/sorting keys do not enforce uniqueness.
	if !out.View {
		out.PrimaryKey, err = browseUniqueKey(ctx, conn, d, out)
	}
	if err != nil {
		if ctx.Err() != nil {
			return out, ctx.Err()
		}
		// Key discovery is optional read metadata. Missing index visibility
		// never grants stability and never turns a readable table into an
		// editable one; the page still executes with the account's rights.
		out.PrimaryKey = nil
	}
	return out, nil
}

// BrowseRelationIsView also detects a table replaced by a view between pages.
// Reads never infer that a sorting key or view projection is a writable identity.
func BrowseRelationIsView(ctx context.Context, conn *sql.Conn, d Dialect, r CatalogColumnRequest) (bool, error) {
	if d == ClickHouse {
		var engine string
		err := conn.QueryRowContext(ctx, `SELECT engine FROM system.tables WHERE database=? AND name=?`, r.Schema, r.Table).Scan(&engine)
		return strings.Contains(strings.ToLower(engine), "view"), err
	}
	q, err := editBaseTableQuery(d, r)
	if err != nil {
		return false, err
	}
	if d == MariaDB {
		q.SQL = strings.Replace(q.SQL, "TABLE_TYPE='BASE TABLE'", "TABLE_TYPE IN ('BASE TABLE','SYSTEM VERSIONED')", 1)
	}
	var kind string
	err = conn.QueryRowContext(ctx, q.SQL, q.Args...).Scan(&kind)
	if errors.Is(err, sql.ErrNoRows) {
		var ok bool
		q, ok = browseViewQuery(d, r)
		if !ok {
			return false, err
		}
		err = conn.QueryRowContext(ctx, q.SQL, q.Args...).Scan(&kind)
	}
	if err != nil {
		return false, err
	}
	view := kind == "VIEW" || d == PostgreSQL && (kind == "v" || kind == "m")
	if !view && (IsMySQLDialect(d) && kind == "" || !IsMySQLDialect(d) && !editBaseTableKind(d, kind)) {
		return false, ErrCatalogNotFound
	}
	return view, nil
}

func browseViewQuery(d Dialect, r CatalogColumnRequest) (catalogQuery, bool) {
	b := catalogBinder{dialect: d}
	s, t := b.bind(r.Schema), b.bind(r.Table)
	q := ""
	switch {
	case IsMySQLDialect(d):
		q = `SELECT 'VIEW' FROM information_schema.TABLES WHERE TABLE_SCHEMA=` + s + ` AND TABLE_NAME=` + t + ` AND TABLE_TYPE IN ('VIEW','SYSTEM VIEW')`
	case d == SQLServer:
		q = `SELECT 'VIEW' FROM sys.views v JOIN sys.schemas s ON s.schema_id=v.schema_id WHERE s.name=` + s + ` AND v.name=` + t
	case IsOracleDialect(d):
		q = `SELECT 'VIEW' FROM ALL_VIEWS WHERE OWNER=` + s + ` AND VIEW_NAME=` + t
	case d == DB2:
		q = `SELECT 'VIEW' FROM SYSCAT.TABLES WHERE TABSCHEMA=` + s + ` AND TABNAME=` + t + ` AND TYPE='V'`
	}
	return catalogQuery{SQL: q, Args: b.args}, q != ""
}

func browseUniqueKey(ctx context.Context, conn *sql.Conn, d Dialect, table EditTable) ([]string, error) {
	q, args := browseUniqueKeySQL(d, table.Schema, table.Table)
	if q == "" {
		return nil, nil
	}
	rows, err := conn.QueryContext(ctx, q, args...)
	if err != nil {
		return nil, fmt.Errorf("read browse ordering keys: %w", err)
	}
	defer rows.Close()
	type key struct {
		columns []string
		count   int
		invalid bool
	}
	keys := map[string]*key{}
	count := 0
	for rows.Next() {
		count++
		if count > 4096 {
			return nil, fmt.Errorf("browse key metadata exceeds its limit")
		}
		var name string
		var column sql.NullString
		var ordinal, total int
		if err := rows.Scan(&name, &column, &ordinal, &total); err != nil {
			return nil, err
		}
		k := keys[name]
		if k == nil {
			k = &key{count: total}
			keys[name] = k
		}
		if name == "" || total < 1 || total > MaxCatalogPageSize || k.count != total || ordinal != len(k.columns)+1 || !column.Valid {
			k.invalid = true
		}
		col := column.String
		if d == DB2 {
			col = strings.TrimRight(col, " ")
		}
		found := false
		for _, c := range table.Columns {
			if c.Name == col {
				found = true
				if c.Nullable || !c.Supported {
					k.invalid = true
				}
				break
			}
		}
		if !found {
			k.invalid = true
		}
		for _, old := range k.columns {
			if old == col {
				k.invalid = true
			}
		}
		k.columns = append(k.columns, col)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	names := make([]string, 0, len(keys))
	for name := range keys {
		names = append(names, name)
	}
	sort.Strings(names)
	var best []string
	for _, name := range names {
		k := keys[name]
		if !k.invalid && len(k.columns) == k.count && (best == nil || len(k.columns) < len(best)) {
			best = k.columns
		}
	}
	return best, nil
}
func browseUniqueKeySQL(d Dialect, schema, table string) (string, []any) {
	b := catalogBinder{dialect: d}
	s, t := b.bind(schema), b.bind(table)
	if d == PostgreSQL {
		s, t = "$1", "$2"
	}
	var q string
	switch {
	case IsMySQLDialect(d):
		// MySQL 5.7 has no window functions. A correlated count preserves complete
		// key size without relying on the visibility of any individual column.
		q = `SELECT x.INDEX_NAME,x.COLUMN_NAME,x.SEQ_IN_INDEX,(SELECT COUNT(*) FROM information_schema.STATISTICS y WHERE BINARY y.TABLE_SCHEMA=BINARY x.TABLE_SCHEMA AND BINARY y.TABLE_NAME=BINARY x.TABLE_NAME AND BINARY y.INDEX_NAME=BINARY x.INDEX_NAME) FROM information_schema.STATISTICS x WHERE BINARY x.TABLE_SCHEMA=BINARY ` + s + ` AND BINARY x.TABLE_NAME=BINARY ` + t + ` AND x.NON_UNIQUE=0 AND NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS z WHERE BINARY z.TABLE_SCHEMA=BINARY x.TABLE_SCHEMA AND BINARY z.TABLE_NAME=BINARY x.TABLE_NAME AND BINARY z.INDEX_NAME=BINARY x.INDEX_NAME AND (z.SUB_PART IS NOT NULL OR z.COLUMN_NAME IS NULL)) ORDER BY BINARY x.INDEX_NAME,x.SEQ_IN_INDEX`
	case d == PostgreSQL:
		q = `SELECT ix.relname,a.attname,u.ord,COALESCE((to_jsonb(i)->>'indnkeyatts')::int,i.indnatts) FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class t ON t.oid=i.indrelid JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace JOIN pg_catalog.pg_class ix ON ix.oid=i.indexrelid CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY u(attnum,ord) LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid=t.oid AND a.attnum=u.attnum JOIN pg_catalog.pg_opclass op ON op.oid=i.indclass[(u.ord-1)::int] WHERE n.nspname=` + s + ` AND t.relname=` + t + ` AND i.indisunique AND i.indisvalid AND i.indisready AND i.indimmediate AND i.indpred IS NULL AND i.indexprs IS NULL AND op.opcdefault AND i.indcollation[(u.ord-1)::int]=a.attcollation AND u.ord<=COALESCE((to_jsonb(i)->>'indnkeyatts')::int,i.indnatts) ORDER BY ix.relname,u.ord`
	case d == SQLServer:
		q = `SELECT i.name,c.name,k.key_ordinal,(SELECT COUNT(*) FROM sys.index_columns z WHERE z.object_id=i.object_id AND z.index_id=i.index_id AND z.key_ordinal>0) FROM sys.indexes i JOIN sys.tables t ON t.object_id=i.object_id JOIN sys.schemas s ON s.schema_id=t.schema_id JOIN sys.index_columns k ON k.object_id=i.object_id AND k.index_id=i.index_id JOIN sys.columns c ON c.object_id=k.object_id AND c.column_id=k.column_id WHERE s.name=` + s + ` AND t.name=` + t + ` AND i.is_unique=1 AND i.is_disabled=0 AND i.is_hypothetical=0 AND i.has_filter=0 AND k.key_ordinal>0 ORDER BY i.name,k.key_ordinal`
	case IsOracleDialect(d):
		// Valid, non-partitioned physical unique indexes cover standalone
		// indexes as well as enforced constraints. Hidden/function key columns
		// and nullable columns are rejected against the visible column set.
		s2, t2 := b.bind(schema), b.bind(table)
		q = `SELECT 'I:'||i.OWNER||'.'||i.INDEX_NAME,k.COLUMN_NAME,k.COLUMN_POSITION,COUNT(*) OVER(PARTITION BY i.OWNER,i.INDEX_NAME) FROM ALL_INDEXES i JOIN ALL_IND_COLUMNS k ON k.INDEX_OWNER=i.OWNER AND k.INDEX_NAME=i.INDEX_NAME AND k.TABLE_OWNER=i.TABLE_OWNER AND k.TABLE_NAME=i.TABLE_NAME WHERE i.TABLE_OWNER=` + s + ` AND i.TABLE_NAME=` + t + ` AND i.UNIQUENESS='UNIQUE' AND i.STATUS='VALID' AND i.PARTITIONED='NO' UNION ALL SELECT 'C:'||c.OWNER||'.'||c.CONSTRAINT_NAME,k.COLUMN_NAME,k.POSITION,COUNT(*) OVER(PARTITION BY c.OWNER,c.CONSTRAINT_NAME) FROM ALL_CONSTRAINTS c JOIN ALL_CONS_COLUMNS k ON k.OWNER=c.OWNER AND k.CONSTRAINT_NAME=c.CONSTRAINT_NAME AND k.TABLE_NAME=c.TABLE_NAME WHERE c.OWNER=` + s2 + ` AND c.TABLE_NAME=` + t2 + ` AND c.CONSTRAINT_TYPE IN ('P','U') AND c.STATUS='ENABLED' AND c.VALIDATED='VALIDATED' AND c.DEFERRABLE='NOT DEFERRABLE' ORDER BY 1,3`

	case d == DB2:
		q = `SELECT i.INDSCHEMA||'.'||i.INDNAME,RTRIM(c.COLNAME),c.COLSEQ,i.COLCOUNT FROM SYSCAT.INDEXES i JOIN SYSCAT.INDEXCOLUSE c ON c.INDSCHEMA=i.INDSCHEMA AND c.INDNAME=i.INDNAME WHERE i.TABSCHEMA=` + s + ` AND i.TABNAME=` + t + ` AND i.UNIQUERULE IN ('P','U') AND c.COLORDER IN ('A','D') ORDER BY i.INDNAME,c.COLSEQ`
	default:
		return "", nil
	}
	return q, b.args
}
