package sqlservice

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"unicode/utf8"
)

const MaxCatalogPageSize = 200

var (
	ErrCatalogForbidden = errors.New("catalog access forbidden")
	ErrCatalogNotFound  = errors.New("catalog object not found")
)

type CatalogSchema struct {
	Database string `json:"database"`
	Name     string `json:"name"`
}
type CatalogObject struct {
	Database   string `json:"database"`
	Schema     string `json:"schema"`
	Name       string `json:"name"`
	Kind       string `json:"kind"`
	ObjectType string `json:"object_type"`
	Comment    string `json:"comment"`
}
type CatalogColumn struct {
	Database string `json:"database"`
	Schema   string `json:"schema"`
	Table    string `json:"table"`
	Name     string `json:"name"`
	DataType string `json:"data_type"`
	Nullable bool   `json:"nullable"`
	Comment  string `json:"comment"`
	Ordinal  int    `json:"-"`
}
type CatalogObjectRequest struct {
	Database, Schema, Kind, Query, AfterSchema, AfterName string
	Limit                                                 int
}
type CatalogColumnRequest struct {
	Database, Schema, Table string
	AfterOrdinal, Limit     int
}

// SchemaRows and the other catalog functions take an immutable authorized
// database from the session layer. They never enumerate other MySQL databases or
// change connection context. Results contain at most limit+1 rows so the HTTP
// layer can create a keyset cursor without running a COUNT query.
func SchemaRows(ctx context.Context, conn *sql.Conn, dialect Dialect, database, after string, limit int) ([]CatalogSchema, error) {
	items := []CatalogSchema{}
	if err := validateCatalogInput(conn, dialect, database, after); err != nil {
		return items, err
	}
	ctx, cancel := context.WithTimeout(ctx, catalogTimeout)
	defer cancel()
	if err := catalogDatabaseAccess(ctx, conn, dialect, database); err != nil {
		return items, err
	}
	var rows *sql.Rows
	var err error
	if extraCatalogDialect(dialect) {
		var query catalogQuery
		query, err = extraSchemaQuery(dialect, database, after, limit)
		if err == nil {
			rows, err = conn.QueryContext(ctx, query.SQL, query.Args...)
		}
	} else if IsMySQLDialect(dialect) {
		rows, err = conn.QueryContext(ctx, `SELECT SCHEMA_NAME, SCHEMA_NAME FROM information_schema.SCHEMATA
WHERE BINARY SCHEMA_NAME = BINARY ? AND BINARY SCHEMA_NAME > BINARY ?
ORDER BY BINARY SCHEMA_NAME LIMIT ?`, database, after, catalogLimit(limit)+1)
	} else {
		rows, err = conn.QueryContext(ctx, `SELECT pg_catalog.current_database(), n.nspname FROM pg_catalog.pg_namespace n
WHERE pg_catalog.current_database() = $1 AND n.nspname NOT LIKE 'pg!_%' ESCAPE '!' AND n.nspname <> 'information_schema'
AND pg_catalog.has_schema_privilege(n.oid, 'USAGE') AND n.nspname COLLATE pg_catalog."C" > $2 COLLATE pg_catalog."C"
ORDER BY n.nspname COLLATE pg_catalog."C" LIMIT $3`, database, after, catalogLimit(limit)+1)
	}
	if err != nil {
		return items, fmt.Errorf("list catalog schemas: %w", err)
	}
	defer rows.Close()
	for rows.Next() {
		var item CatalogSchema
		if err := rows.Scan(&item.Database, &item.Name); err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	return items, rows.Err()
}

func ObjectRows(ctx context.Context, conn *sql.Conn, dialect Dialect, req CatalogObjectRequest) ([]CatalogObject, error) {
	items := []CatalogObject{}
	if err := validateCatalogInput(conn, dialect, req.Database, req.Schema, req.Query, req.AfterSchema, req.AfterName); err != nil {
		return items, err
	}
	if req.Kind != "" && req.Kind != "table" && req.Kind != "view" {
		return items, fmt.Errorf("catalog kind must be table or view")
	}
	if req.Schema != "" && systemCatalogSchema(req.Schema, dialect) {
		return items, ErrCatalogForbidden
	}
	if (IsMySQLDialect(dialect) || dialect == ClickHouse) && req.Schema != "" && req.Schema != req.Database {
		return items, ErrCatalogForbidden
	}
	ctx, cancel := context.WithTimeout(ctx, catalogTimeout)
	defer cancel()
	if err := catalogDatabaseAccess(ctx, conn, dialect, req.Database); err != nil {
		return items, err
	}
	if dialect == PostgreSQL && req.Schema != "" {
		if err := catalogSchemaAccess(ctx, conn, req.Schema); err != nil {
			return items, err
		}
	}
	var rows *sql.Rows
	var err error
	if extraCatalogDialect(dialect) {
		var query catalogQuery
		query, err = extraObjectQuery(dialect, req, false, catalogLimit(req.Limit)+1)
		if err == nil {
			rows, err = conn.QueryContext(ctx, query.SQL, query.Args...)
		}
	} else if IsMySQLDialect(dialect) {
		// MySQL's INFORMATION_SCHEMA applies the current account's table and
		// column privileges itself, including active roles. No grant parsing or
		// elevated helper account is used.
		rows, err = conn.QueryContext(ctx, `SELECT TABLE_SCHEMA, TABLE_SCHEMA, TABLE_NAME,
CASE WHEN TABLE_TYPE = 'VIEW' THEN 'view' ELSE 'table' END,
CASE WHEN TABLE_TYPE = 'VIEW' THEN 'view' ELSE 'table' END, COALESCE(TABLE_COMMENT, '')
FROM information_schema.TABLES
WHERE BINARY TABLE_SCHEMA = BINARY ? AND TABLE_TYPE IN ('BASE TABLE', 'VIEW')
AND (? = '' OR (? = 'table' AND TABLE_TYPE = 'BASE TABLE') OR (? = 'view' AND TABLE_TYPE = 'VIEW'))
AND TABLE_NAME LIKE ? ESCAPE '!'
AND (BINARY TABLE_SCHEMA > BINARY ? OR (BINARY TABLE_SCHEMA = BINARY ? AND BINARY TABLE_NAME > BINARY ?))
ORDER BY BINARY TABLE_SCHEMA, BINARY TABLE_NAME LIMIT ?`, req.Database, req.Kind, req.Kind, req.Kind, catalogSearch(req.Query), req.AfterSchema, req.AfterSchema, req.AfterName, catalogLimit(req.Limit)+1)
	} else {
		rows, err = conn.QueryContext(ctx, `SELECT pg_catalog.current_database(), n.nspname, c.relname,
CASE WHEN c.relkind IN ('v', 'm') THEN 'view' ELSE 'table' END,
CASE c.relkind WHEN 'm' THEN 'materialized_view' WHEN 'f' THEN 'foreign_table' WHEN 'p' THEN 'partitioned_table' WHEN 'v' THEN 'view' ELSE 'table' END,
COALESCE(pg_catalog.obj_description(c.oid, 'pg_class'), '')
FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
WHERE pg_catalog.current_database() = $1 AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
AND n.nspname NOT LIKE 'pg!_%' ESCAPE '!' AND n.nspname <> 'information_schema'
AND ($2 = '' OR n.nspname = $2) AND pg_catalog.has_schema_privilege(n.oid, 'USAGE')
AND (pg_catalog.has_table_privilege(c.oid, 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') OR pg_catalog.has_any_column_privilege(c.oid, 'SELECT,INSERT,UPDATE,REFERENCES'))
AND ($3 = '' OR ($3 = 'table' AND c.relkind IN ('r', 'p', 'f')) OR ($3 = 'view' AND c.relkind IN ('v', 'm')))
AND c.relname ILIKE $4 ESCAPE '!'
AND (n.nspname COLLATE pg_catalog."C", c.relname COLLATE pg_catalog."C") > ($5 COLLATE pg_catalog."C", $6 COLLATE pg_catalog."C")
ORDER BY n.nspname COLLATE pg_catalog."C", c.relname COLLATE pg_catalog."C" LIMIT $7`, req.Database, req.Schema, req.Kind, catalogSearch(req.Query), req.AfterSchema, req.AfterName, catalogLimit(req.Limit)+1)
	}
	if err != nil {
		return items, fmt.Errorf("list catalog objects: %w", err)
	}
	defer rows.Close()
	for rows.Next() {
		var item CatalogObject
		var comment sql.NullString
		if err := rows.Scan(&item.Database, &item.Schema, &item.Name, &item.Kind, &item.ObjectType, &comment); err != nil {
			return nil, err
		}
		item.Comment = comment.String
		items = append(items, item)
	}
	return items, rows.Err()
}

func ColumnRows(ctx context.Context, conn *sql.Conn, dialect Dialect, req CatalogColumnRequest) ([]CatalogColumn, error) {
	items := []CatalogColumn{}
	if err := validateCatalogInput(conn, dialect, req.Database, req.Schema, req.Table); err != nil {
		return items, err
	}
	if req.Table == "" || req.AfterOrdinal < 0 {
		return items, fmt.Errorf("catalog table and nonnegative ordinal are required")
	}
	if (IsMySQLDialect(dialect) || dialect == ClickHouse) && req.Schema == "" {
		req.Schema = req.Database
	}
	if req.Schema == "" {
		return items, fmt.Errorf("catalog schema is required")
	}
	if systemCatalogSchema(req.Schema, dialect) || (IsMySQLDialect(dialect) || dialect == ClickHouse) && req.Schema != req.Database {
		return items, ErrCatalogForbidden
	}
	ctx, cancel := context.WithTimeout(ctx, catalogTimeout)
	defer cancel()
	if err := catalogDatabaseAccess(ctx, conn, dialect, req.Database); err != nil {
		return items, err
	}
	if err := catalogObjectAccess(ctx, conn, dialect, req.Schema, req.Table); err != nil {
		return items, err
	}
	var rows *sql.Rows
	var err error
	if extraCatalogDialect(dialect) {
		var query catalogQuery
		query, err = extraColumnQuery(dialect, req, catalogLimit(req.Limit)+1)
		if err == nil {
			rows, err = conn.QueryContext(ctx, query.SQL, query.Args...)
		}
	} else if IsMySQLDialect(dialect) {
		rows, err = conn.QueryContext(ctx, `SELECT TABLE_SCHEMA, TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, COLUMN_TYPE,
IS_NULLABLE = 'YES', COALESCE(COLUMN_COMMENT, ''), ORDINAL_POSITION
FROM information_schema.COLUMNS
WHERE BINARY TABLE_SCHEMA = BINARY ? AND BINARY TABLE_NAME = BINARY ? AND ORDINAL_POSITION > ?
ORDER BY ORDINAL_POSITION LIMIT ?`, req.Schema, req.Table, req.AfterOrdinal, catalogLimit(req.Limit)+1)
	} else {
		rows, err = conn.QueryContext(ctx, `SELECT pg_catalog.current_database(), n.nspname, c.relname, a.attname,
pg_catalog.format_type(a.atttypid, a.atttypmod), NOT a.attnotnull, COALESCE(pg_catalog.col_description(a.attrelid, a.attnum), ''), a.attnum
FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
WHERE pg_catalog.current_database() = $1 AND n.nspname = $2 AND c.relname = $3 AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
AND a.attnum > $4 AND NOT a.attisdropped AND pg_catalog.has_schema_privilege(n.oid, 'USAGE')
AND pg_catalog.has_column_privilege(c.oid, a.attnum, 'SELECT,INSERT,UPDATE,REFERENCES')
ORDER BY a.attnum LIMIT $5`, req.Database, req.Schema, req.Table, req.AfterOrdinal, catalogLimit(req.Limit)+1)
	}
	if err != nil {
		return items, fmt.Errorf("list catalog columns: %w", err)
	}
	defer rows.Close()
	for rows.Next() {
		var item CatalogColumn
		var comment sql.NullString
		if err := rows.Scan(&item.Database, &item.Schema, &item.Table, &item.Name, &item.DataType, &item.Nullable, &comment, &item.Ordinal); err != nil {
			return nil, err
		}
		item.Comment = comment.String
		items = append(items, item)
	}
	return items, rows.Err()
}

func catalogDatabaseAccess(ctx context.Context, conn *sql.Conn, dialect Dialect, database string) error {
	if extraCatalogDialect(dialect) {
		return extraCatalogDatabaseAccess(ctx, conn, dialect, database)
	}
	if systemCatalogSchema(database, MySQL) && IsMySQLDialect(dialect) {
		return ErrCatalogForbidden
	}
	var allowed bool
	var err error
	if IsMySQLDialect(dialect) {
		err = conn.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM information_schema.SCHEMATA WHERE BINARY SCHEMA_NAME = BINARY ?)`, database).Scan(&allowed)
	} else {
		err = conn.QueryRowContext(ctx, `SELECT pg_catalog.current_database() = $1`, database).Scan(&allowed)
	}
	if err != nil {
		return fmt.Errorf("check catalog database: %w", err)
	}
	if !allowed {
		return ErrCatalogForbidden
	}
	return nil
}
func catalogSchemaAccess(ctx context.Context, conn *sql.Conn, schema string) error {
	var allowed bool
	err := conn.QueryRowContext(ctx, `SELECT pg_catalog.has_schema_privilege(n.oid, 'USAGE') FROM pg_catalog.pg_namespace n WHERE n.nspname = $1`, schema).Scan(&allowed)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrCatalogNotFound
	}
	if err != nil {
		return fmt.Errorf("check catalog schema: %w", err)
	}
	if !allowed {
		return ErrCatalogForbidden
	}
	return nil
}
func catalogObjectAccess(ctx context.Context, conn *sql.Conn, dialect Dialect, schema, table string) error {
	if extraCatalogDialect(dialect) {
		return extraCatalogObjectAccess(ctx, conn, dialect, schema, table)
	}
	if IsMySQLDialect(dialect) {
		var exists bool
		err := conn.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM information_schema.TABLES
WHERE BINARY TABLE_SCHEMA = BINARY ? AND BINARY TABLE_NAME = BINARY ? AND TABLE_TYPE IN ('BASE TABLE', 'VIEW'))`, schema, table).Scan(&exists)
		if err != nil {
			return fmt.Errorf("check catalog object: %w", err)
		}
		// MySQL hides inaccessible tables in INFORMATION_SCHEMA. Absence may
		// mean nonexistent or hidden; do not probe user tables to distinguish.
		if !exists {
			return ErrCatalogNotFound
		}
		return nil
	}
	var schemaAllowed, objectAllowed, columnsAllowed bool
	err := conn.QueryRowContext(ctx, `SELECT pg_catalog.has_schema_privilege(n.oid, 'USAGE'),
(pg_catalog.has_table_privilege(c.oid, 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') OR pg_catalog.has_any_column_privilege(c.oid, 'SELECT,INSERT,UPDATE,REFERENCES')),
pg_catalog.has_any_column_privilege(c.oid, 'SELECT,INSERT,UPDATE,REFERENCES')
FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = $1 AND c.relname = $2 AND c.relkind IN ('r', 'p', 'v', 'm', 'f')`, schema, table).Scan(&schemaAllowed, &objectAllowed, &columnsAllowed)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrCatalogNotFound
	}
	if err != nil {
		return fmt.Errorf("check catalog object: %w", err)
	}
	if !schemaAllowed || !objectAllowed || !columnsAllowed {
		return ErrCatalogForbidden
	}
	return nil
}
func validateCatalogInput(conn *sql.Conn, dialect Dialect, database string, values ...string) error {
	if !supported(dialect) {
		return ErrUnsupportedDialect
	}
	if conn == nil {
		return fmt.Errorf("catalog connection is required")
	}
	if database == "" {
		return ErrCatalogForbidden
	}
	for _, value := range append([]string{database}, values...) {
		if len(value) > 256 || !utf8.ValidString(value) || strings.ContainsRune(value, 0) {
			return fmt.Errorf("catalog identifier or search exceeds bounds")
		}
	}
	return nil
}
func systemCatalogSchema(schema string, dialect Dialect) bool {
	if dialect == PostgreSQL {
		return schema == "information_schema" || strings.HasPrefix(schema, "pg_")
	}
	if IsOracleDialect(dialect) {
		switch strings.ToUpper(schema) {
		case "SYS", "SYSTEM", "CTXSYS", "MDSYS", "XDB", "DBSNMP", "OUTLN":
			return true
		}
		return false
	}
	if dialect == DB2 {
		return strings.HasPrefix(strings.ToUpper(schema), "SYS")
	}
	if dialect == ClickHouse {
		return schema == "system" || strings.EqualFold(schema, "information_schema")
	}
	if dialect == SQLServer {
		return strings.EqualFold(schema, "sys") || strings.EqualFold(schema, "information_schema")
	}
	switch strings.ToLower(schema) {
	case "mysql", "sys", "performance_schema", "information_schema":
		return true
	}
	return false
}
func catalogSearch(query string) string {
	return "%" + strings.TrimSuffix(likePrefix(query), "%") + "%"
}
func catalogLimit(limit int) int {
	if limit <= 0 || limit > MaxCatalogPageSize {
		return MaxCatalogPageSize
	}
	return limit
}
