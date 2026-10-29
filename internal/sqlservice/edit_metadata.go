package sqlservice

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
)

// ErrEditColumnFlagsUnknown never includes a driver message or user values.
var ErrEditColumnFlagsUnknown = errors.New("This database version or account does not expose reliable generated/identity column flags")

type EditColumn struct {
	Name       string `json:"name"`
	DataType   string `json:"data_type"`
	Nullable   bool   `json:"nullable"`
	Generated  bool   `json:"generated"`
	Identity   bool   `json:"identity"`
	HasDefault bool   `json:"has_default"`
	Default    string `json:"default,omitempty"`
	Supported  bool   `json:"supported"`
	PrimaryKey bool   `json:"primary_key"`
}

type EditTable struct {
	Database   string       `json:"database"`
	Schema     string       `json:"schema"`
	Table      string       `json:"table"`
	Columns    []EditColumn `json:"columns"`
	PrimaryKey []string     `json:"primary_key"`
}

// LoadEditTable reads the authenticated connection's visible metadata. Missing
// metadata, views and nontransactional tables fail closed; no user-table query
// is used as an alternate discovery path. The 200-column bound must never turn
// a partial table definition into permission to insert an incomplete row.
func LoadEditTable(ctx context.Context, conn *sql.Conn, d Dialect, scope Context, schema, table string) (EditTable, error) {
	out := EditTable{Database: scope.Database, Schema: schema, Table: table}
	if d == ClickHouse || !supported(d) {
		return out, fmt.Errorf("%w: transactional grid editing is unavailable", ErrNotEditable)
	}
	if err := validateCatalogInput(conn, d, scope.Database, schema, table); err != nil {
		return out, err
	}
	if table == "" {
		return out, ErrCatalogNotFound
	}
	ctx, cancel := context.WithTimeout(ctx, catalogTimeout)
	defer cancel()
	if err := catalogDatabaseAccess(ctx, conn, d, scope.Database); err != nil {
		return out, err
	}
	if IsMySQLDialect(d) {
		if schema == "" {
			schema = scope.Database
		}
		if schema != scope.Database {
			return out, ErrCatalogForbidden
		}
	} else if d == PostgreSQL && schema == "" {
		// Resolve against the actual search_path (which user SQL can change),
		// never assume that a cached workspace schema names the selected table.
		var resolved string
		err := conn.QueryRowContext(ctx, `SELECT n.nspname FROM pg_catalog.pg_class t JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace WHERE t.oid=pg_catalog.to_regclass($1)`, quoteIdentifier(table, d, '"')).Scan(&resolved)
		if err != nil {
			return out, fmt.Errorf("resolve editable table: %w", err)
		}
		schema = resolved
	} else if schema == "" {
		var err error
		schema, err = extraDefaultSchema(ctx, conn, d, scope.Database)
		if err != nil {
			return out, err
		}
		if d == SQLServer {
			// SQL Server first searches the default schema and then dbo.
			var resolved string
			err = conn.QueryRowContext(ctx, `SELECT OBJECT_SCHEMA_NAME(OBJECT_ID(@p1, 'U'))`, quoteIdentifier(table, d, '[')).Scan(&resolved)
			if err != nil {
				return out, fmt.Errorf("resolve editable table: %w", err)
			}
			schema = resolved
		}
	}
	if systemCatalogSchema(schema, d) {
		return out, ErrCatalogForbidden
	}
	out.Schema = schema
	if err := catalogObjectAccess(ctx, conn, d, schema, table); err != nil {
		if !IsMySQLDialect(d) || !errors.Is(err, ErrCatalogNotFound) {
			return out, err
		}
		// MySQL-compatible servers with lower_case_table_names=1/2 resolve
		// even quoted table references without case sensitivity. Preserve
		// strict lookup on case-sensitive servers and resolve only a unique,
		// visible catalog name; never probe user rows to guess the target.
		table, err = editMySQLCanonicalTable(ctx, conn, schema, table)
		if err != nil {
			return out, err
		}
		out.Table = table
	}
	r := CatalogColumnRequest{Database: scope.Database, Schema: schema, Table: table, Limit: MaxCatalogPageSize}
	base, err := editBaseTableQuery(d, r)
	if err != nil {
		return out, err
	}
	var kind string
	if err := conn.QueryRowContext(ctx, base.SQL, base.Args...).Scan(&kind); err != nil {
		return out, fmt.Errorf("read editable table kind: %w", err)
	}
	if !editBaseTableKind(d, kind) {
		return out, fmt.Errorf("%w: a transactional base table is required", ErrNotEditable)
	}
	cols, err := ColumnRows(ctx, conn, d, r)
	if err != nil {
		return out, err
	}
	if len(cols) == 0 || len(cols) > MaxCatalogPageSize {
		return out, fmt.Errorf("%w: incomplete column metadata", ErrNotEditable)
	}
	flagsQuery, err := editColumnFlagsQuery(d, r)
	if err != nil {
		return out, err
	}
	rows, err := conn.QueryContext(ctx, flagsQuery.SQL, flagsQuery.Args...)
	if err != nil {
		return out, fmt.Errorf("read generated column metadata: %w", err)
	}
	flags := map[string]EditColumn{}
	for rows.Next() {
		var col EditColumn
		var generated, identity sql.NullBool
		if err = rows.Scan(&col.Name, &generated, &identity, &col.HasDefault); err != nil {
			break
		}
		if !generated.Valid || !identity.Valid {
			err = ErrEditColumnFlagsUnknown
			break
		}
		col.Generated, col.Identity = generated.Bool, identity.Bool
		if d == DB2 {
			col.Name = strings.TrimRight(col.Name, " ")
		}
		key := editNameKey(col.Name, d)
		if _, duplicate := flags[key]; duplicate {
			err = fmt.Errorf("ambiguous column metadata")
			break
		}
		flags[key] = col
		if len(flags) > MaxCatalogPageSize {
			err = fmt.Errorf("column metadata exceeds editing limit")
			break
		}
	}
	if err == nil {
		err = rows.Err()
	}
	_ = rows.Close()
	if err != nil {
		return out, fmt.Errorf("read complete generated column metadata: %w", err)
	}
	for _, col := range cols {
		item, ok := flags[editNameKey(col.Name, d)]
		if !ok {
			return out, fmt.Errorf("%w: generated column metadata is missing", ErrNotEditable)
		}
		item.Name, item.DataType, item.Nullable = col.Name, col.DataType, col.Nullable
		item.Supported = EditTypeSupported(d, col.DataType)
		out.Columns = append(out.Columns, item)
	}
	pk, _, ok := inspectionQuery(d, r, "primary_key")
	if !ok {
		return out, fmt.Errorf("%w: primary key metadata is unavailable", ErrNotEditable)
	}
	rows, err = conn.QueryContext(ctx, pk.SQL, pk.Args...)
	if err != nil {
		return out, fmt.Errorf("read primary key: %w", err)
	}
	for rows.Next() {
		var constraint, name string
		var ordinal int
		if err = rows.Scan(&constraint, &name, &ordinal); err != nil {
			break
		}
		if d == DB2 {
			name = strings.TrimRight(name, " ")
		}
		if len(out.PrimaryKey) >= MaxCatalogPageSize {
			err = fmt.Errorf("primary key metadata exceeds bounds")
			break
		}
		found := false
		for i := range out.Columns {
			if editNameKey(out.Columns[i].Name, d) == editNameKey(name, d) {
				out.Columns[i].PrimaryKey = true
				found = true
				break
			}
		}
		if !found {
			err = fmt.Errorf("primary key column is not visible")
			break
		}
		out.PrimaryKey = append(out.PrimaryKey, name)
	}
	if err == nil {
		err = rows.Err()
	}
	_ = rows.Close()
	if err != nil {
		return out, fmt.Errorf("read complete primary key: %w", err)
	}
	return out, ctx.Err()
}

func editMySQLCanonicalTable(ctx context.Context, conn *sql.Conn, schema, table string) (string, error) {
	var mode int
	if err := conn.QueryRowContext(ctx, `SELECT @@lower_case_table_names`).Scan(&mode); err != nil {
		return "", fmt.Errorf("resolve editable table case mode: %w", err)
	}
	if mode != 1 && mode != 2 {
		return "", ErrCatalogNotFound
	}
	rows, err := conn.QueryContext(ctx, `SELECT TABLE_NAME FROM information_schema.TABLES WHERE BINARY TABLE_SCHEMA=BINARY ? AND BINARY LOWER(TABLE_NAME)=BINARY LOWER(?) AND TABLE_TYPE IN ('BASE TABLE','VIEW') LIMIT 2`, schema, table)
	if err != nil {
		return "", fmt.Errorf("resolve editable table name: %w", err)
	}
	defer rows.Close()
	var name string
	count := 0
	for rows.Next() {
		if err := rows.Scan(&name); err != nil {
			return "", err
		}
		count++
	}
	if err := rows.Err(); err != nil {
		return "", err
	}
	if count != 1 {
		return "", ErrCatalogNotFound
	}
	return name, nil
}

func editBaseTableKind(d Dialect, kind string) bool {
	if IsMySQLDialect(d) {
		return strings.EqualFold(kind, "InnoDB")
	}
	if d == PostgreSQL {
		return kind == "r" || kind == "p"
	}
	return kind == "TABLE"
}

func editBaseTableQuery(d Dialect, r CatalogColumnRequest) (catalogQuery, error) {
	b := catalogBinder{dialect: d}
	s, t := b.bind(r.Schema), b.bind(r.Table)
	if d == PostgreSQL {
		s, t = "$1", "$2"
	}
	var q string
	switch {
	case IsMySQLDialect(d):
		q = `SELECT COALESCE(ENGINE,'') FROM information_schema.TABLES WHERE BINARY TABLE_SCHEMA=BINARY ` + s + ` AND BINARY TABLE_NAME=BINARY ` + t + ` AND TABLE_TYPE='BASE TABLE'`
	case d == PostgreSQL:
		q = `SELECT t.relkind::text FROM pg_catalog.pg_class t JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname=` + s + ` AND t.relname=` + t
	case d == SQLServer:
		q = `SELECT 'TABLE' FROM sys.tables t JOIN sys.schemas s ON s.schema_id=t.schema_id WHERE s.name=` + s + ` AND t.name=` + t + ` AND t.is_ms_shipped=0`
	case IsOracleDialect(d):
		q = `SELECT 'TABLE' FROM ALL_TABLES WHERE OWNER=` + s + ` AND TABLE_NAME=` + t
	case d == DB2:
		q = `SELECT 'TABLE' FROM SYSCAT.TABLES WHERE TABSCHEMA=` + s + ` AND TABNAME=` + t + ` AND TYPE='T'`
	default:
		return catalogQuery{}, ErrUnsupportedDialect
	}
	return catalogQuery{SQL: q, Args: b.args}, nil
}

func editColumnFlagsQuery(d Dialect, r CatalogColumnRequest) (catalogQuery, error) {
	b := catalogBinder{dialect: d}
	s, t := b.bind(r.Schema), b.bind(r.Table)
	if d == PostgreSQL {
		s, t = "$1", "$2"
	}
	var q string
	switch {
	case IsMySQLDialect(d):
		q = `SELECT COLUMN_NAME, CASE WHEN EXTRA LIKE '%GENERATED%' AND EXTRA NOT LIKE '%DEFAULT_GENERATED%' THEN 1 ELSE 0 END, CASE WHEN EXTRA LIKE '%auto_increment%' THEN 1 ELSE 0 END, CASE WHEN COLUMN_DEFAULT IS NOT NULL THEN 1 ELSE 0 END FROM information_schema.COLUMNS WHERE BINARY TABLE_SCHEMA=BINARY ` + s + ` AND BINARY TABLE_NAME=BINARY ` + t + ` ORDER BY ORDINAL_POSITION`
	case d == PostgreSQL:
		q = `SELECT a.attname, COALESCE(to_jsonb(a)->>'attgenerated','')<>'', COALESCE(to_jsonb(a)->>'attidentity','')<>'', ad.oid IS NOT NULL FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class t ON t.oid=a.attrelid JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace LEFT JOIN pg_catalog.pg_attrdef ad ON ad.adrelid=t.oid AND ad.adnum=a.attnum WHERE n.nspname=` + s + ` AND t.relname=` + t + ` AND a.attnum>0 AND NOT a.attisdropped AND pg_catalog.has_column_privilege(t.oid,a.attnum,` + postgresColumnPrivileges + `) ORDER BY a.attnum`
	case d == SQLServer:
		q = `SELECT c.name, CASE WHEN c.is_computed=1 OR c.generated_always_type<>0 OR c.system_type_id=189 THEN 1 ELSE 0 END, c.is_identity, CASE WHEN c.default_object_id<>0 THEN 1 ELSE 0 END FROM sys.columns c JOIN sys.tables t ON t.object_id=c.object_id JOIN sys.schemas s ON s.schema_id=t.schema_id WHERE s.name=` + s + ` AND t.name=` + t + ` ORDER BY c.column_id`
	case d == Dameng:
		// DM documents SYSCOLUMNS.INFO2 bit 0 as identity. Use object IDs so
		// same-named tables in other schemas cannot influence writable flags.
		q = `SELECT c.COLUMN_NAME, CASE WHEN c.VIRTUAL_COLUMN='YES' THEN 1 ELSE 0 END, CASE WHEN BITAND(sc.INFO2,1)=1 THEN 1 ELSE 0 END, CASE WHEN c.DEFAULT_LENGTH>0 THEN 1 ELSE 0 END FROM ALL_TAB_COLS c JOIN ALL_OBJECTS o ON o.OWNER=c.OWNER AND o.OBJECT_NAME=c.TABLE_NAME AND o.OBJECT_TYPE='TABLE' JOIN SYS.SYSCOLUMNS sc ON sc.ID=o.OBJECT_ID AND sc.NAME=c.COLUMN_NAME WHERE c.OWNER=` + s + ` AND c.TABLE_NAME=` + t + ` ORDER BY c.COLUMN_ID`
	case d == OceanBaseOracle:
		// Several OB releases expose IDENTITY_COLUMN as a NULL placeholder.
		// Preserve unknown instead of treating it as a non-identity column.
		q = `SELECT COLUMN_NAME, CASE WHEN VIRTUAL_COLUMN='YES' THEN 1 WHEN VIRTUAL_COLUMN='NO' THEN 0 ELSE NULL END, CASE WHEN IDENTITY_COLUMN='YES' THEN 1 WHEN IDENTITY_COLUMN='NO' THEN 0 ELSE NULL END, CASE WHEN DEFAULT_LENGTH>0 THEN 1 ELSE 0 END FROM ALL_TAB_COLS WHERE OWNER=` + s + ` AND TABLE_NAME=` + t + ` AND HIDDEN_COLUMN='NO' ORDER BY COLUMN_ID`
	case IsOracleDialect(d):
		q = `SELECT COLUMN_NAME, CASE WHEN VIRTUAL_COLUMN='YES' THEN 1 ELSE 0 END, CASE WHEN IDENTITY_COLUMN='YES' THEN 1 ELSE 0 END, CASE WHEN DEFAULT_LENGTH>0 THEN 1 ELSE 0 END FROM ALL_TAB_COLS WHERE OWNER=` + s + ` AND TABLE_NAME=` + t + ` AND HIDDEN_COLUMN='NO' ORDER BY COLUMN_ID`
	case d == DB2:
		q = `SELECT RTRIM(COLNAME), CASE WHEN GENERATED IN ('A','D') THEN 1 ELSE 0 END, CASE WHEN IDENTITY='Y' THEN 1 ELSE 0 END, CASE WHEN DEFAULT IS NOT NULL THEN 1 ELSE 0 END FROM SYSCAT.COLUMNS WHERE TABSCHEMA=` + s + ` AND TABNAME=` + t + ` ORDER BY COLNO`
	default:
		return catalogQuery{}, ErrUnsupportedDialect
	}
	return catalogQuery{SQL: q, Args: b.args}, nil
}

// EditTypeSupported is intentionally narrower than result display. Binary,
// arrays, spatial/user-defined types and LOB locators need explicit codecs.
func EditTypeSupported(d Dialect, dataType string) bool {
	t := strings.ToLower(strings.TrimSpace(dataType))
	if i := strings.IndexByte(t, '('); i >= 0 {
		end := strings.IndexByte(t[i:], ')')
		if end < 0 {
			return false
		}
		t = strings.TrimSpace(t[:i] + t[i+end+1:])
	}
	if strings.HasSuffix(t, " unsigned") && IsMySQLDialect(d) {
		t = strings.TrimSuffix(t, " unsigned")
	}
	if d == SQLServer && (t == "timestamp" || t == "rowversion") {
		return false
	}
	switch t {
	case "tinyint", "smallint", "mediumint", "int", "integer", "bigint", "int2", "int4", "int8", "smallserial", "serial", "bigserial", "decimal", "numeric", "number", "dec", "real", "float", "double", "double precision", "float4", "float8", "binary_float", "binary_double":
		return true
	case "char", "character", "varchar", "character varying", "varchar2", "nvarchar", "nvarchar2", "nchar", "text", "ntext", "tinytext", "mediumtext", "longtext", "graphic", "vargraphic", "long varchar":
		return true
	case "date", "time", "time without time zone", "timestamp", "timestamp without time zone", "datetime", "datetime2", "smalldatetime":
		return true
	case "timestamp with time zone", "timestamptz", "datetimeoffset":
		return d == PostgreSQL || d == Oracle || d == SQLServer
	case "boolean", "bool":
		return d == PostgreSQL || IsMySQLDialect(d) || d == DB2
	case "bit":
		return d == SQLServer
	case "uuid":
		return d == PostgreSQL
	case "uniqueidentifier":
		return d == SQLServer
	case "json", "jsonb":
		return d == PostgreSQL || IsMySQLDialect(d)
	default:
		return false
	}
}
