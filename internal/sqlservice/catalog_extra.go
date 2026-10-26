package sqlservice

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
)

// The additional catalogs use the authenticated connection only. Database and
// schema names remain bound values, never multipart identifiers or USE commands.
// ALL_* and sys.* expose only metadata visible to the connected account.
type catalogQuery struct {
	SQL  string
	Args []any
}

type catalogBinder struct {
	dialect Dialect
	args    []any
}

func (b *catalogBinder) bind(value any) string {
	b.args = append(b.args, value)
	switch b.dialect {
	case SQLServer:
		return fmt.Sprintf("@p%d", len(b.args))
	case Oracle:
		return fmt.Sprintf(":%d", len(b.args))
	default:
		return "?"
	}
}

func (b *catalogBinder) orderedPage(query string, limit int) catalogQuery {
	// limit is an internal, bounded integer. Literal limits also support Db2
	// versions whose FETCH FIRST syntax does not accept a parameter marker.
	switch b.dialect {
	case SQLServer:
		query = strings.Replace(query, "SELECT ", fmt.Sprintf("SELECT TOP (%d) ", limit), 1)
	case Oracle, Dameng, OceanBaseOracle:
		query = fmt.Sprintf("SELECT * FROM (%s) WHERE ROWNUM <= %d", query, limit)
	case DB2:
		query += fmt.Sprintf(" FETCH FIRST %d ROWS ONLY", limit)
	case ClickHouse:
		query += fmt.Sprintf(" LIMIT %d", limit)
	}
	return catalogQuery{query, b.args}
}

func extraCatalogDialect(d Dialect) bool {
	return d == SQLServer || d == Oracle || d == Dameng || d == DB2 || d == ClickHouse || d == OceanBaseOracle
}

// Db2's SYSCAT is public by default, so reading it without privilege filtering
// would reveal other accounts' objects. Resolve effective users/groups/roles
// with server routines, including DATAACCESS authority. If those routines are
// unavailable to the account, the request fails rather than broadening scope.
const db2Principals = `(SELECT CURRENT USER AS AUTHID, 'U' AS AUTHTYPE FROM SYSIBM.SYSDUMMY1
UNION SELECT 'PUBLIC', 'G' FROM SYSIBM.SYSDUMMY1
UNION SELECT "GROUP", 'G' FROM TABLE(SYSPROC.AUTH_LIST_GROUPS_FOR_AUTHID(CURRENT USER)) AS G
UNION SELECT ROLENAME, 'R' FROM TABLE(SYSPROC.AUTH_LIST_ROLES_FOR_AUTHID(CURRENT USER, 'U')) AS R)`

const db2TableVisible = `(t.OWNER = CURRENT USER OR EXISTS (
SELECT 1 FROM SYSCAT.TABAUTH a JOIN ` + db2Principals + ` p ON p.AUTHID = a.GRANTEE AND p.AUTHTYPE = a.GRANTEETYPE
WHERE a.TABSCHEMA = t.TABSCHEMA AND a.TABNAME = t.TABNAME AND (a.SELECTAUTH IN ('Y','G') OR a.CONTROLAUTH = 'Y'))
OR EXISTS (SELECT 1 FROM TABLE(SYSPROC.AUTH_LIST_AUTHORITIES_FOR_AUTHID(CURRENT USER, 'U')) AS a
WHERE a.AUTHORITY = 'DATAACCESS' AND 'Y' IN (a.D_USER,a.D_GROUP,a.D_PUBLIC,a.ROLE_USER,a.ROLE_GROUP,a.ROLE_PUBLIC,a.D_ROLE)))`

func extraObjectSource(d Dialect, b *catalogBinder, database string) (string, error) {
	switch d {
	case SQLServer:
		return `SELECT DB_NAME() AS dbname, s.name AS schemaname, o.name AS objectname,
CASE WHEN o.type = 'V' THEN 'view' ELSE 'table' END AS objectkind,
CASE WHEN o.type = 'V' THEN 'view' ELSE 'table' END AS objecttype,
COALESCE(CONVERT(nvarchar(4000), ep.value), '') AS objectcomment
FROM sys.objects o JOIN sys.schemas s ON s.schema_id = o.schema_id
LEFT JOIN sys.extended_properties ep ON ep.class = 1 AND ep.major_id = o.object_id AND ep.minor_id = 0 AND ep.name = 'MS_Description'
WHERE o.type IN ('U','V') AND o.is_ms_shipped = 0 AND s.name NOT IN ('sys','INFORMATION_SCHEMA')`, nil
	case Oracle, Dameng, OceanBaseOracle:
		// ALL_TABLES/ALL_VIEWS can push an owner predicate into the dictionary.
		// ALL_OBJECTS plus a correlated materialized-view lookup is much more
		// expensive for broadly granted accounts (including built-in APEX).
		return `SELECT ` + b.bind(database) + ` AS dbname, o.OWNER AS schemaname, o.OBJECT_NAME AS objectname,
o.OBJECT_KIND AS objectkind, o.OBJECT_KIND AS objecttype, c.COMMENTS AS objectcomment
FROM (SELECT OWNER, TABLE_NAME AS OBJECT_NAME, 'table' AS OBJECT_KIND FROM ALL_TABLES
UNION ALL SELECT OWNER, VIEW_NAME AS OBJECT_NAME, 'view' AS OBJECT_KIND FROM ALL_VIEWS) o
LEFT JOIN ALL_TAB_COMMENTS c ON c.OWNER = o.OWNER AND c.TABLE_NAME = o.OBJECT_NAME
WHERE o.OWNER NOT IN ('SYS','SYSTEM','CTXSYS','MDSYS','XDB','DBSNMP','OUTLN')` + oracleApplicationOwner(d, "o.OWNER"), nil
	case DB2:
		return `SELECT RTRIM(CURRENT SERVER) AS dbname, RTRIM(t.TABSCHEMA) AS schemaname, RTRIM(t.TABNAME) AS objectname,
CASE WHEN t.TYPE = 'V' THEN 'view' ELSE 'table' END AS objectkind,
CASE WHEN t.TYPE = 'V' THEN 'view' WHEN t.TYPE = 'S' THEN 'materialized_view' WHEN t.TYPE = 'N' THEN 'foreign_table' ELSE 'table' END AS objecttype,
COALESCE(t.REMARKS, '') AS objectcomment
FROM SYSCAT.TABLES t WHERE t.TYPE IN ('T','V','S','N') AND t.TABSCHEMA NOT LIKE 'SYS%' AND ` + db2TableVisible, nil
	case ClickHouse:
		// Local system tables apply SHOW TABLES visibility. Do not replace this
		// with cluster()/remote(), which can have different visibility semantics.
		return `SELECT database AS dbname, database AS schemaname, name AS objectname,
if(engine IN ('View','MaterializedView','LiveView','WindowView'), 'view', 'table') AS objectkind,
if(engine = 'MaterializedView', 'materialized_view', if(engine IN ('View','LiveView','WindowView'), 'view', 'table')) AS objecttype,
comment AS objectcomment FROM system.tables WHERE is_temporary = 0 AND database = ` + b.bind(database), nil
	}
	return "", ErrUnsupportedDialect
}

func extraOrder(d Dialect, column string) string {
	if d == SQLServer {
		return column + " COLLATE Latin1_General_100_BIN2"
	}
	if IsOracleDialect(d) {
		return "NLSSORT(" + column + ", 'NLS_SORT=BINARY')"
	}
	return column
}

func oracleApplicationOwner(d Dialect, owner string) string {
	if d == Oracle {
		// Oracle 12c+ explicitly marks maintained accounts. Avoid fragile APEX
		// version-name lists, and do not assume this column exists in DM/OB.
		return " AND " + owner + " IN (SELECT USERNAME FROM ALL_USERS WHERE ORACLE_MAINTAINED = 'N')"
	}
	return ""
}

func extraSchemaQuery(d Dialect, database, after string, limit int) (catalogQuery, error) {
	b := &catalogBinder{dialect: d}
	var source string
	if IsOracleDialect(d) {
		// Schema discovery needs owners only. ALL_OBJECTS + comments and a
		// correlated materialized-view lookup can scan the whole dictionary
		// before returning a single owner on heavily granted Oracle accounts.
		source = "SELECT " + b.bind(database) + " AS dbname, OWNER AS schemaname FROM (SELECT OWNER FROM ALL_TABLES UNION SELECT OWNER FROM ALL_VIEWS)"
	} else {
		var err error
		source, err = extraObjectSource(d, b, database)
		if err != nil {
			return catalogQuery{}, err
		}
	}
	// Keep the current schema visible even when it has no objects. Other
	// schemas are discovered exclusively through accessible table metadata.
	current := ""
	switch d {
	case SQLServer:
		current = "SELECT DB_NAME() AS dbname, COALESCE(SCHEMA_NAME(), 'dbo') AS schemaname"
	case Oracle, Dameng, OceanBaseOracle:
		current = "SELECT " + b.bind(database) + " AS dbname, SYS_CONTEXT('USERENV','CURRENT_SCHEMA') AS schemaname FROM DUAL"
	case DB2:
		current = "SELECT RTRIM(CURRENT SERVER) AS dbname, RTRIM(CURRENT SCHEMA) AS schemaname FROM SYSIBM.SYSDUMMY1"
	case ClickHouse:
		current = "SELECT currentDatabase() AS dbname, currentDatabase() AS schemaname"
	}
	union := " UNION "
	if d == ClickHouse {
		// Bare UNION depends on union_default_mode, whose default is empty.
		union = " UNION DISTINCT "
	}
	query := "SELECT dbname, schemaname FROM (SELECT DISTINCT dbname, schemaname FROM (" + source + ") objects" + union + current + ") schemas WHERE 1 = 1"
	if d == SQLServer {
		query += " AND schemaname NOT IN ('sys', 'INFORMATION_SCHEMA')"
	} else if IsOracleDialect(d) {
		query += " AND schemaname NOT IN ('SYS', 'SYSTEM', 'CTXSYS', 'MDSYS', 'XDB', 'DBSNMP', 'OUTLN')"
		query += oracleApplicationOwner(d, "schemaname")
	} else if d == DB2 {
		query += " AND schemaname NOT LIKE 'SYS%'"
	} else if d == ClickHouse {
		query += " AND schemaname NOT IN ('system', 'information_schema', 'INFORMATION_SCHEMA')"
	}
	if after != "" {
		query += " AND " + extraOrder(d, "schemaname") + " > " + extraOrder(d, b.bind(after))
	}
	query += " ORDER BY " + extraOrder(d, "schemaname")
	return b.orderedPage(query, catalogLimit(limit)+1), nil
}

func extraObjectQuery(d Dialect, req CatalogObjectRequest, prefix bool, limit int) (catalogQuery, error) {
	b := &catalogBinder{dialect: d}
	source, err := extraObjectSource(d, b, req.Database)
	if err != nil {
		return catalogQuery{}, err
	}
	query := "SELECT dbname, schemaname, objectname, objectkind, objecttype, objectcomment FROM (" + source + ") objects WHERE 1 = 1"
	if req.Schema != "" {
		query += " AND schemaname = " + b.bind(req.Schema)
	}
	if req.Kind != "" {
		query += " AND objectkind = " + b.bind(req.Kind)
	}
	if req.Query != "" {
		pattern := catalogSearch(req.Query)
		if prefix {
			pattern = likePrefix(req.Query)
		}
		if d == ClickHouse {
			// ClickHouse LIKE uses backslash escaping and has no ESCAPE clause.
			pattern = "%" + clickHouseLike(req.Query) + "%"
			if prefix {
				pattern = clickHouseLike(req.Query) + "%"
			}
			query += " AND lowerUTF8(objectname) LIKE lowerUTF8(" + b.bind(pattern) + ")"
		} else if d == DB2 {
			// Db2 cannot infer a parameter marker's character type inside LOWER.
			query += " AND LOWER(objectname) LIKE LOWER(CAST(" + b.bind(pattern) + " AS VARCHAR(1024))) ESCAPE '!'"
		} else {
			query += " AND LOWER(objectname) LIKE LOWER(" + b.bind(pattern) + ") ESCAPE '!'"
		}
	}
	if req.AfterSchema != "" || req.AfterName != "" {
		// Each occurrence is bound separately for positional-marker drivers.
		query += " AND (" + extraOrder(d, "schemaname") + " > " + extraOrder(d, b.bind(req.AfterSchema)) + " OR (schemaname = " + b.bind(req.AfterSchema) + " AND " + extraOrder(d, "objectname") + " > " + extraOrder(d, b.bind(req.AfterName)) + "))"
	}
	query += " ORDER BY " + extraOrder(d, "schemaname") + ", " + extraOrder(d, "objectname")
	return b.orderedPage(query, limit), nil
}

func clickHouseLike(value string) string {
	return strings.NewReplacer(`\`, `\\`, "%", `\%`, "_", `\_`).Replace(value)
}

func extraColumnQuery(d Dialect, req CatalogColumnRequest, limit int) (catalogQuery, error) {
	b := &catalogBinder{dialect: d}
	var query string
	switch d {
	case SQLServer:
		query = `SELECT DB_NAME(), s.name, o.name, c.name,
t.name + CASE WHEN t.name IN ('varchar','char','varbinary','binary') THEN '(' + CASE WHEN c.max_length = -1 THEN 'max' ELSE CONVERT(varchar(10),c.max_length) END + ')'
WHEN t.name IN ('nvarchar','nchar') THEN '(' + CASE WHEN c.max_length = -1 THEN 'max' ELSE CONVERT(varchar(10),c.max_length / 2) END + ')'
WHEN t.name IN ('decimal','numeric') THEN '(' + CONVERT(varchar(10),c.precision) + ',' + CONVERT(varchar(10),c.scale) + ')' ELSE '' END,
c.is_nullable, COALESCE(CONVERT(nvarchar(4000),ep.value), ''), c.column_id
FROM sys.columns c JOIN sys.objects o ON o.object_id = c.object_id JOIN sys.schemas s ON s.schema_id = o.schema_id
JOIN sys.types t ON t.user_type_id = c.user_type_id
LEFT JOIN sys.extended_properties ep ON ep.class = 1 AND ep.major_id = c.object_id AND ep.minor_id = c.column_id AND ep.name = 'MS_Description'
WHERE o.type IN ('U','V') AND o.is_ms_shipped = 0 AND s.name = ` + b.bind(req.Schema) + " AND o.name = " + b.bind(req.Table) + " AND c.column_id > " + b.bind(req.AfterOrdinal) + " ORDER BY c.column_id"
	case Oracle, Dameng, OceanBaseOracle:
		query = `SELECT ` + b.bind(req.Database) + `, c.OWNER, c.TABLE_NAME, c.COLUMN_NAME, c.DATA_TYPE,
CASE WHEN c.NULLABLE = 'Y' THEN 1 ELSE 0 END, cc.COMMENTS, c.COLUMN_ID
FROM ALL_TAB_COLUMNS c LEFT JOIN ALL_COL_COMMENTS cc ON cc.OWNER = c.OWNER AND cc.TABLE_NAME = c.TABLE_NAME AND cc.COLUMN_NAME = c.COLUMN_NAME
WHERE c.OWNER = ` + b.bind(req.Schema) + " AND c.TABLE_NAME = " + b.bind(req.Table) + " AND c.COLUMN_ID > " + b.bind(req.AfterOrdinal) + " ORDER BY c.COLUMN_ID"
	case DB2:
		query = `SELECT RTRIM(CURRENT SERVER), RTRIM(c.TABSCHEMA), RTRIM(c.TABNAME), RTRIM(c.COLNAME), c.TYPENAME,
CASE WHEN c.NULLS = 'Y' THEN 1 ELSE 0 END, COALESCE(c.REMARKS, ''), c.COLNO + 1
FROM SYSCAT.COLUMNS c JOIN SYSCAT.TABLES t ON t.TABSCHEMA = c.TABSCHEMA AND t.TABNAME = c.TABNAME
WHERE c.TABSCHEMA = ` + b.bind(req.Schema) + " AND c.TABNAME = " + b.bind(req.Table) + " AND c.COLNO + 1 > " + b.bind(req.AfterOrdinal) + " AND " + db2TableVisible + " ORDER BY c.COLNO"
	case ClickHouse:
		query = `SELECT database, database, table, name, type,
(startsWith(type, 'Nullable(') OR startsWith(type, 'LowCardinality(Nullable(')), comment, position
FROM system.columns WHERE database = ` + b.bind(req.Database) + " AND table = " + b.bind(req.Table) + " AND position > " + b.bind(req.AfterOrdinal) + " ORDER BY position"
	default:
		return catalogQuery{}, ErrUnsupportedDialect
	}
	return b.orderedPage(query, limit), nil
}

func extraCatalogDatabaseAccess(ctx context.Context, conn *sql.Conn, dialect Dialect, database string) error {
	var current string
	var query string
	switch dialect {
	case SQLServer:
		query = "SELECT DB_NAME()"
	case DB2:
		query = "SELECT RTRIM(CURRENT SERVER) FROM SYSIBM.SYSDUMMY1"
	case ClickHouse:
		query = "SELECT currentDatabase()"
	case Oracle, Dameng, OceanBaseOracle:
		// Oracle-family Database identifies a connection service, not a schema.
		// ALL_* stays in that service; comparing it to DB_NAME would incorrectly
		// reject PDB/service aliases. Session authorization fixes this value and
		// the connection itself, and requests cannot switch either of them.
		return nil
	default:
		return ErrUnsupportedDialect
	}
	if err := conn.QueryRowContext(ctx, query).Scan(&current); err != nil {
		return fmt.Errorf("check catalog database: %w", err)
	}
	if current != database {
		return ErrCatalogForbidden
	}
	return nil
}

func extraCatalogObjectAccess(ctx context.Context, conn *sql.Conn, d Dialect, schema, table string) error {
	b := &catalogBinder{dialect: d}
	source, err := extraObjectSource(d, b, schema)
	if err != nil {
		return err
	}
	query := "SELECT objectname FROM (" + source + ") objects WHERE schemaname = " + b.bind(schema) + " AND objectname = " + b.bind(table)
	var name string
	if err := conn.QueryRowContext(ctx, query, b.args...).Scan(&name); err != nil {
		if err == sql.ErrNoRows {
			return ErrCatalogNotFound
		}
		return fmt.Errorf("check catalog object: %w", err)
	}
	return nil
}
