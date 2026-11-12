package engine

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/jumpserver/chen/internal/sqlservice"
	"strings"
)

func vendorQueryPageContext(ctx context.Context, c *console, d string) (string, error) {
	var query string
	switch d {
	case "sqlserver":
		query = `SELECT COALESCE(DB_NAME(),''),COALESCE(SCHEMA_NAME(),''),SUSER_SNAME(),USER_NAME(),CONVERT(varchar(20),@@OPTIONS),@@LANGUAGE,CONVERT(varchar(20),@@DATEFIRST)`
	case "oracle", "dameng", "oceanbase-oracle":
		query = `SELECT SYS_CONTEXT('USERENV','DB_NAME'),SYS_CONTEXT('USERENV','CURRENT_SCHEMA'),SYS_CONTEXT('USERENV','CURRENT_USER'),SYS_CONTEXT('USERENV','SESSION_USER'),SESSIONTIMEZONE,DBTIMEZONE FROM DUAL`
	case "db2":
		query = `SELECT RTRIM(CURRENT SERVER),RTRIM(CURRENT SCHEMA),SESSION_USER,CURRENT USER,CURRENT PATH,CHAR(CURRENT TIMEZONE) FROM SYSIBM.SYSDUMMY1`
	case "clickhouse":
		query = `SELECT currentDatabase(),currentUser(),timezone()`
	default:
		return "", errors.New("Database query paging is unavailable for this dialect")
	}
	rows, err := c.conn.QueryContext(ctx, query)
	if err != nil {
		return "", err
	}
	cols, err := rows.Columns()
	if err != nil {
		rows.Close()
		return "", err
	}
	values := make([]string, len(cols))
	dest := make([]any, len(cols))
	for i := range values {
		dest[i] = &values[i]
	}
	if !rows.Next() {
		err = rows.Err()
		rows.Close()
		if err == nil {
			err = errors.New("Unable to verify the paging session context")
		}
		return "", err
	}
	if err = rows.Scan(dest...); err != nil {
		rows.Close()
		return "", err
	}
	if rows.Next() {
		rows.Close()
		return "", errors.New("Unexpected paging context result")
	}
	if err = rows.Err(); err != nil {
		rows.Close()
		return "", err
	}
	rows.Close()
	settings := ""
	switch d {
	case "sqlserver":
		settings = "DBCC USEROPTIONS WITH NO_INFOMSGS"
	case "oracle", "dameng", "oceanbase-oracle":
		settings = "SELECT PARAMETER,COALESCE(VALUE,'') FROM NLS_SESSION_PARAMETERS ORDER BY PARAMETER"
	case "clickhouse":
		// clickhouse-go derives max_execution_time from each request's context
		// deadline. Admission and execution have different deadlines; this query
		// budget is not a change to the console's session semantics.
		settings = "SELECT name,value FROM system.settings WHERE changed AND name != 'max_execution_time' ORDER BY name"
	}
	if settings != "" {
		rows, err = c.conn.QueryContext(ctx, settings)
		if err != nil {
			return "", err
		}
		defer rows.Close()
		n := 0
		for rows.Next() {
			var key, value string
			if err := rows.Scan(&key, &value); err != nil {
				return "", err
			}
			values = append(values, key, value)
			n++
			if n > 1024 {
				return "", errors.New("Paging settings exceed the context limit")
			}
		}
		if err := rows.Err(); err != nil {
			return "", err
		}
	}
	raw, _ := json.Marshal(values)
	return string(raw), nil
}

func vendorQueryPageSource(ctx context.Context, c *console, d string, r sqlservice.QueryPageRelation, safe *bool) error {
	switch d {
	case "sqlserver":
		var version string
		var edition int
		if err := c.conn.QueryRowContext(ctx, `SELECT CONVERT(varchar(128),SERVERPROPERTY('ProductVersion')),CONVERT(int,SERVERPROPERTY('EngineEdition'))`).Scan(&version, &edition); err != nil {
			return err
		}
		var major int
		if _, err := fmt.Sscanf(version, "%d.", &major); err != nil || major < 9 {
			return errors.New("Database paging requires verified SQL Server 2005 or later metadata")
		}
		qualified := quoteDatabaseIdentifier(r.Name, "sqlserver")
		if r.Schema != "" {
			qualified = quoteDatabaseIdentifier(r.Schema, "sqlserver") + "." + qualified
		}
		// Resolve the original name first, including dbo fallback. Filtering
		// through sys.tables afterwards cannot skip a shadowing view/synonym.
		predicate := `NOT EXISTS(SELECT 1 FROM sys.columns c WHERE c.object_id=t.object_id AND (c.is_computed=1 OR c.user_type_id<>c.system_type_id))`
		if major >= 13 || edition == 5 || edition == 8 {
			var visible bool
			if err := c.conn.QueryRowContext(ctx, `SELECT CASE WHEN HAS_PERMS_BY_NAME(DB_NAME(),'DATABASE','VIEW DEFINITION')=1 OR HAS_PERMS_BY_NAME(NULL,NULL,'VIEW ANY DEFINITION')=1 OR HAS_PERMS_BY_NAME(DB_NAME(),'DATABASE','ALTER ANY SECURITY POLICY')=1 THEN 1 ELSE 0 END`).Scan(&visible); err != nil {
				return err
			}
			if !visible {
				return errors.New("Database paging cannot verify hidden SQL Server row-security policies with this account's metadata visibility; use cached results")
			}
			predicate = `t.is_external=0 AND ` + predicate + ` AND NOT EXISTS(SELECT 1 FROM sys.security_predicates p JOIN sys.security_policies x ON x.object_id=p.object_id WHERE p.target_object_id=t.object_id AND x.is_enabled=1)`
		}
		return c.conn.QueryRowContext(ctx, "SELECT CASE WHEN "+predicate+" THEN 1 ELSE 0 END FROM sys.tables t WHERE t.object_id=OBJECT_ID(@p1)", qualified).Scan(safe)
	case "oracle", "dameng", "oceanbase-oracle":
		owner := r.Schema
		if owner == "" {
			if err := c.conn.QueryRowContext(ctx, `SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM DUAL`).Scan(&owner); err != nil {
				return err
			}
		}
		var one int
		err := c.conn.QueryRowContext(ctx, `SELECT 1 FROM ALL_TABLES WHERE OWNER=:1 AND TABLE_NAME=:2`, owner, r.Name).Scan(&one)
		if errors.Is(err, sql.ErrNoRows) && d == "oracle" && r.Schema == "" && r.Name == "DUAL" {
			var matches, local int
			if err = c.conn.QueryRowContext(ctx, `SELECT COUNT(*) FROM ALL_OBJECTS WHERE OWNER=:1 AND OBJECT_NAME='DUAL'`, owner).Scan(&local); err != nil {
				return err
			}
			if local != 0 {
				*safe = false
				return nil
			}
			err = c.conn.QueryRowContext(ctx, `SELECT COUNT(*) FROM ALL_SYNONYMS WHERE OWNER='PUBLIC' AND SYNONYM_NAME='DUAL' AND TABLE_OWNER='SYS' AND TABLE_NAME='DUAL' AND DB_LINK IS NULL`).Scan(&matches)
			*safe = matches == 1
			return err
		}
		if err != nil {
			return err
		}
		// Independent exact-key reads avoid deeply correlated Oracle catalog
		// views and NULL-typed schema binds while preserving every guard.
		var unsafe int
		for _, query := range []string{
			`SELECT COUNT(*) FROM ALL_TAB_COLS WHERE OWNER=:1 AND TABLE_NAME=:2 AND (VIRTUAL_COLUMN IS NULL OR VIRTUAL_COLUMN<>'NO' OR DATA_TYPE_OWNER IS NOT NULL)`,
			`SELECT COUNT(*) FROM ALL_EXTERNAL_TABLES WHERE OWNER=:1 AND TABLE_NAME=:2`,
			`SELECT COUNT(*) FROM ALL_POLICIES WHERE OBJECT_OWNER=:1 AND OBJECT_NAME=:2 AND (ENABLE IS NULL OR ENABLE<>'NO')`,
		} {
			if err := c.conn.QueryRowContext(ctx, query, owner, r.Name).Scan(&unsafe); err != nil {
				return err
			}
			if unsafe != 0 {
				*safe = false
				return nil
			}
		}
		*safe = true
		return nil

	case "db2":
		var level string
		if err := c.conn.QueryRowContext(ctx, `SELECT SERVICE_LEVEL FROM TABLE(SYSPROC.ENV_GET_INST_INFO()) AS CHEN_INSTANCE`).Scan(&level); err != nil {
			return err
		}
		version := strings.TrimSpace(level)
		if strings.HasPrefix(strings.ToUpper(version), "DB2 V") {
			version = version[5:]
		}
		var major, minor int
		if _, err := fmt.Sscanf(version, "%d.%d", &major, &minor); err != nil || major < 9 || major == 9 && minor < 7 {
			return errors.New("Database paging requires verified DB2 LUW 9.7 or later metadata")
		}
		predicate := `t.TYPE='T' AND NOT EXISTS(SELECT 1 FROM SYSCAT.COLUMNS c WHERE c.TABSCHEMA=t.TABSCHEMA AND c.TABNAME=t.TABNAME AND (c.TYPESCHEMA<>'SYSIBM' OR (c.GENERATED='A' AND c.IDENTITY<>'Y')))`
		if major >= 10 {
			predicate += ` AND NOT EXISTS(SELECT 1 FROM SYSCAT.CONTROLS x WHERE x.TABSCHEMA=t.TABSCHEMA AND x.TABNAME=t.TABNAME AND x.ENABLE='Y')`
		}
		return c.conn.QueryRowContext(ctx, "SELECT CASE WHEN "+predicate+" THEN 1 ELSE 0 END FROM SYSCAT.TABLES t WHERE t.TABSCHEMA=COALESCE(NULLIF(?,''),CURRENT SCHEMA) AND t.TABNAME=?", r.Schema, r.Name).Scan(safe)
	case "clickhouse":
		var engine string
		err := c.conn.QueryRowContext(ctx, `SELECT engine FROM system.tables WHERE database=if(?='',currentDatabase(),?) AND name=?`, r.Schema, r.Schema, r.Name).Scan(&engine)
		// External/executable storage can hide behavior absent from the SQL AST.
		*safe = engine == "Memory" || engine == "MergeTree" || strings.HasSuffix(engine, "MergeTree")
		if err != nil || !*safe {
			return err
		}
		// Missing old-part columns can evaluate DEFAULT/MATERIALIZED on read,
		// not just ALIAS. Inspect every hidden expression before replay.
		rows, err := c.conn.QueryContext(ctx, `SELECT default_expression FROM system.columns WHERE database=if(?='',currentDatabase(),?) AND table=? AND default_expression<>''`, r.Schema, r.Schema, r.Name)
		if err != nil {
			return err
		}
		functions := []string{}
		n := 0
		for rows.Next() {
			n++
			var expr string
			if err = rows.Scan(&expr); err != nil {
				break
			}
			if n > 2048 || len(expr) > 16384 {
				err = errors.New("Hidden column expressions exceed the source inspection limit")
				break
			}
			parsed, e := sqlservice.AnalyzeQueryPage(sqlservice.ClickHouse, "SELECT "+expr, sqlservice.AnalyzeOptions{})
			if e != nil || len(parsed.Relations) != 0 {
				err = errors.New("Database paging cannot verify a hidden ClickHouse column expression")
				break
			}
			functions = append(functions, parsed.Functions...)
		}
		if err == nil {
			err = rows.Err()
		}
		rows.Close()
		if err != nil {
			return err
		}
		if err = validateVendorQueryFunctions(ctx, c, d, functions); err != nil {
			return err
		}
		var policies int
		if err = c.conn.QueryRowContext(ctx, `SELECT count() FROM system.row_policies WHERE database=if(?='',currentDatabase(),?) AND table=?`, r.Schema, r.Schema, r.Name).Scan(&policies); err != nil {
			return err
		}
		*safe = policies == 0
		return nil

	default:
		return errors.New("Unverified query paging source dialect")
	}
}

// Unqualified vendor built-ins can be shadowed through schema/path resolution.
// A conservative catalog check denies any ambiguous visible overload/synonym.
func validateVendorQueryFunctions(ctx context.Context, c *console, d string, functions []string) error {
	seen := map[string]bool{}
	for _, name := range functions {
		// Names already use dialect canonical spelling. Quoted function calls are rejected by the grammar; never fold quoted bare references.
		if seen[name] {
			continue
		}
		seen[name] = true
		var shadows int
		var err error
		switch d {
		case "sqlserver":
			continue // T-SQL scalar UDFs require a schema qualifier; the closed grammar rejects it.
		case "oracle", "dameng", "oceanbase-oracle":
			var owner string
			err = c.conn.QueryRowContext(ctx, `SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM DUAL`).Scan(&owner)
			if err == nil {
				err = c.conn.QueryRowContext(ctx, `SELECT COUNT(*) FROM ALL_PROCEDURES WHERE OWNER=:1 AND OBJECT_NAME=:2`, owner, name).Scan(&shadows)
			}
			if err == nil && shadows == 0 {
				err = c.conn.QueryRowContext(ctx, `SELECT COUNT(*) FROM ALL_SYNONYMS WHERE OWNER IN(:1,'PUBLIC') AND SYNONYM_NAME=:2`, owner, name).Scan(&shadows)
			}
		case "db2":
			err = c.conn.QueryRowContext(ctx, `SELECT COUNT(*) FROM SYSCAT.ROUTINES WHERE ROUTINENAME=? AND ROUTINESCHEMA NOT IN('SYSIBM','SYSFUN','SYSPROC','SYSIBMADM')`, name).Scan(&shadows)
		case "clickhouse":
			err = c.conn.QueryRowContext(ctx, `SELECT count() FROM system.functions WHERE upper(name)=? AND origin<>'System'`, strings.ToUpper(name)).Scan(&shadows)
		default:
			return errors.New("Unverified query function resolution")
		}
		if err != nil || shadows != 0 {
			return errors.New("Database paging cannot verify that each selected function resolves only to a native built-in")
		}
	}
	return nil
}
