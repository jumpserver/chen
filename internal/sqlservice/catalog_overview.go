package sqlservice

import (
	"context"
	"database/sql"
	"fmt"
	"strconv"
	"strings"
	"time"
)

// Values remain decimal strings across JSON and JavaScript. State is never
// inferred from truthiness: a supported "0" differs from unknown and denied.
type OverviewValue struct {
	State  string  `json:"state"`
	Value  *string `json:"value"`
	Source string  `json:"source,omitempty"`
}
type OverviewRow struct {
	Object  CatalogObject            `json:"object"`
	Name    string                   `json:"name"`
	Ordinal int                      `json:"ordinal,omitempty"`
	Fields  map[string]OverviewValue `json:"fields"`
}
type CatalogOverview struct {
	Database   string                   `json:"database"`
	Schema     string                   `json:"schema"`
	Section    string                   `json:"section"`
	State      string                   `json:"state"`
	ErrorCode  string                   `json:"error_code,omitempty"`
	SampledAt  string                   `json:"sampled_at"`
	Snapshot   bool                     `json:"snapshot"`
	Visibility string                   `json:"visibility"`
	Rows       []OverviewRow            `json:"items"`
	Fields     map[string]OverviewValue `json:"fields"`
	HasMore    bool                     `json:"has_more"`
	NextCursor string                   `json:"next_cursor,omitempty"`
}
type OverviewRequest struct {
	CatalogObjectRequest
	Section, Sort, AfterTable string
	AfterOrdinal              int
}

func overviewValue(v sql.NullString, state, source string) OverviewValue {
	if v.Valid {
		return OverviewValue{State: "supported", Value: &v.String, Source: source}
	}
	return OverviewValue{State: state, Source: source}
}
func overviewText(value, source string) OverviewValue {
	return overviewValue(sql.NullString{String: value, Valid: true}, "unknown", source)
}

// CatalogOverviewRows performs section-level system catalog queries, never
// business COUNT or per-object inspection. Scope is the connection's fixed DB;
// an empty PG schema explicitly means all visible non-system schemas.
func CatalogOverviewRows(ctx context.Context, conn *sql.Conn, d Dialect, req OverviewRequest) (CatalogOverview, error) {
	result := CatalogOverview{Database: req.Database, Schema: req.Schema, Section: req.Section, State: "supported", SampledAt: time.Now().UTC().Format(time.RFC3339Nano), Visibility: "authorized_catalog", Rows: []OverviewRow{}, Fields: map[string]OverviewValue{}}
	if err := validateCatalogInput(conn, d, req.Database, req.Schema, req.Query, req.AfterSchema, req.AfterName, req.AfterTable); err != nil {
		return result, err
	}
	if req.Sort != "name_asc" && req.Sort != "name_desc" {
		return result, fmt.Errorf("invalid overview sort")
	}
	if systemCatalogSchema(req.Schema, d) || (IsMySQLDialect(d) || d == ClickHouse) && req.Schema != "" && req.Schema != req.Database {
		return result, ErrCatalogForbidden
	}
	if err := catalogDatabaseAccess(ctx, conn, d, req.Database); err != nil {
		return result, err
	}
	if d == PostgreSQL && req.Schema != "" {
		if err := catalogSchemaAccess(ctx, conn, req.Schema); err != nil {
			return result, err
		}
	}
	// MySQL-compatible vendors keep their existing directory visibility. Deep
	// properties are explicitly unsupported until validated for that vendor.
	if d != PostgreSQL && d != MySQL {
		if req.Section != "tables" && req.Section != "views" {
			result.State = "unsupported"
			return result, nil
		}
		if req.Sort == "name_desc" {
			result.State = "unsupported"
			return result, nil
		}
		req.Kind = "table"
		if req.Section == "views" {
			req.Kind = "view"
		}
		objects, err := ObjectRows(ctx, conn, d, req.CatalogObjectRequest)
		for _, o := range objects {
			result.Rows = append(result.Rows, OverviewRow{Object: o, Name: o.Name, Fields: map[string]OverviewValue{"comment": overviewText(o.Comment, "catalog"), "estimated_rows": {State: "unsupported"}, "data_bytes": {State: "unsupported"}, "index_bytes": {State: "unsupported"}, "total_bytes": {State: "unsupported"}, "last_analyzed": {State: "unsupported"}, "engine": {State: "unsupported"}, "character_set": {State: "unsupported"}, "collation": {State: "unsupported"}}})
		}
		return result, err
	}
	query, args := overviewSQL(d, req)
	if query == "" {
		result.State = "unsupported"
		return result, nil
	}
	rows, err := conn.QueryContext(ctx, query, args...)
	if err != nil {
		return result, err
	}
	defer rows.Close()
	if req.Section == "statistics" || req.Section == "properties" {
		names, err := rows.Columns()
		if err != nil {
			return result, err
		}
		if rows.Next() {
			values := make([]sql.NullString, len(names))
			dest := make([]any, len(names))
			for i := range values {
				dest[i] = &values[i]
			}
			if err := rows.Scan(dest...); err != nil {
				return result, err
			}
			for i, n := range names {
				result.Fields[n] = overviewValue(values[i], "unknown", overviewSource(d, req.Section, n))
			}
			if req.Section == "statistics" {
				for _, key := range []string{"estimated_rows", "data_bytes", "index_bytes", "total_bytes"} {
					unknown := result.Fields[key+"_unknown"].Value
					if unknown != nil && *unknown == "0" && result.Fields[key].Value == nil {
						result.Fields[key] = overviewText("0", overviewSource(d, req.Section, key))
					}
					if unknown != nil && *unknown != "0" {
						v := result.Fields[key]
						v.State = "partial"
						result.Fields[key] = v
					}
				}
			}
		}
		result.Fields["schema_ddl"] = OverviewValue{State: "unsupported"}
		return result, rows.Err()
	}
	for rows.Next() {
		var row OverviewRow
		row.Fields = map[string]OverviewValue{}
		if req.Section == "indexes" {
			var name, unique, method, definition, column, expression, position, direction, prefix sql.NullString
			if err := rows.Scan(&row.Object.Database, &row.Object.Schema, &row.Object.Name, &row.Object.Kind, &row.Object.ObjectType, &name, &unique, &method, &definition, &column, &expression, &position, &direction, &prefix); err != nil {
				return result, err
			}
			row.Name = name.String
			row.Ordinal, _ = strconv.Atoi(position.String)
			for k, v := range map[string]sql.NullString{"unique": unique, "method": method, "definition": definition, "column": column, "expression": expression, "position": position, "direction": direction, "prefix_length": prefix} {
				state := "unknown"
				if d == PostgreSQL && k == "definition" {
					state = "restricted"
				}
				if (d == PostgreSQL && k != "definition" && k != "unique" && k != "method") || (d == MySQL && k == "definition") {
					state = "unsupported"
				}
				f := overviewValue(v, state, overviewSource(d, req.Section, k))
				if v.Valid && len(v.String) > 16384 {
					short := v.String[:16384]
					for !validOverviewText(short) {
						short = short[:len(short)-1]
					}
					f.Value = &short
					f.State = "truncated"
				}
				row.Fields[k] = f
			}
		} else {
			var comment sql.NullString
			vals := make([]sql.NullString, 8)
			dest := []any{&row.Object.Database, &row.Object.Schema, &row.Object.Name, &row.Object.Kind, &row.Object.ObjectType, &comment}
			for i := range vals {
				dest = append(dest, &vals[i])
			}
			if err := rows.Scan(dest...); err != nil {
				return result, err
			}
			row.Name = row.Object.Name
			row.Object.Comment = comment.String
			row.Fields["comment"] = overviewValue(comment, "unknown", "catalog")
			if len(comment.String) > 4096 {
				text := comment.String[:4096]
				for !validOverviewText(text) {
					text = text[:len(text)-1]
				}
				row.Object.Comment = text
				row.Fields["comment"] = OverviewValue{State: "truncated", Value: &text, Source: "catalog"}
			}
			storage := row.Object.ObjectType == "table" || row.Object.ObjectType == "materialized_view"
			for i, k := range []string{"estimated_rows", "data_bytes", "index_bytes", "total_bytes", "last_analyzed", "engine", "character_set", "collation"} {
				state := "unknown"
				if d == PostgreSQL && (i >= 5 || !storage && i <= 4) || d == MySQL && row.Object.Kind == "view" {
					state = "unsupported"
				}
				row.Fields[k] = overviewValue(vals[i], state, overviewSource(d, req.Section, k))
			}
		}
		result.Rows = append(result.Rows, row)
	}
	return result, rows.Err()
}
func validOverviewText(v string) bool { return strings.ToValidUTF8(v, "") == v }
func overviewSource(d Dialect, section, key string) string {
	if d == PostgreSQL {
		switch key {
		case "estimated_rows":
			return "pg_class.reltuples; negative=unknown; partition parents/foreign tables excluded"
		case "data_bytes":
			return "pg_table_size; includes TOAST and auxiliary forks; physical relations only"
		case "index_bytes":
			return "pg_indexes_size; physical relations only"
		case "total_bytes":
			return "pg_total_relation_size; includes TOAST/indexes; physical relations only"
		case "last_analyzed":
			return "pg_stat_all_tables.last_analyze/last_autoanalyze; freshness may be unknown"
		}
		return "pg_catalog; visible objects only"
	}
	if section == "indexes" {
		return "information_schema.STATISTICS; one row per ordered index part; MySQL 8+"
	}
	if key == "last_analyzed" {
		return "unknown; UPDATE_TIME is not ANALYZE freshness"
	}
	return "information_schema; TABLE_ROWS is engine-dependent estimate; DATA_LENGTH includes clustered data; INDEX_LENGTH secondary indexes; partition totals reported by TABLES; external LOB storage may be excluded"
}

const overviewPGFrom = ` FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
 WHERE pg_catalog.current_database()=$1 AND ($2='' OR n.nspname=$2)
 AND n.nspname NOT LIKE 'pg!_%' ESCAPE '!' AND n.nspname<>'information_schema'
 AND pg_catalog.has_schema_privilege(n.oid,'USAGE')
 AND (pg_catalog.has_table_privilege(c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') OR pg_catalog.has_any_column_privilege(c.oid,'SELECT,INSERT,UPDATE,REFERENCES'))`
const overviewMyFrom = ` FROM information_schema.TABLES t WHERE BINARY t.TABLE_SCHEMA=BINARY ? AND t.TABLE_TYPE IN ('BASE TABLE','VIEW')`

func overviewSQL(d Dialect, r OverviewRequest) (string, []any) {
	desc := r.Sort == "name_desc"
	op, order := ">", "ASC"
	if desc {
		op, order = "<", "DESC"
	}
	if r.Section == "properties" {
		if d == PostgreSQL {
			return `SELECT pg_catalog.pg_encoding_to_char(encoding) AS encoding, datcollate AS collation, datctype AS character_classification FROM pg_catalog.pg_database WHERE datname=$1`, []any{r.Database}
		}
		return `SELECT DEFAULT_CHARACTER_SET_NAME AS character_set, DEFAULT_COLLATION_NAME AS collation FROM information_schema.SCHEMATA WHERE BINARY SCHEMA_NAME=BINARY ?`, []any{r.Database}
	}
	if r.Section == "statistics" {
		if d == PostgreSQL {
			return `SELECT COUNT(*) FILTER(WHERE c.relkind IN ('r','p','f'))::text AS tables, COUNT(*) FILTER(WHERE c.relkind IN ('v','m'))::text AS views,
 SUM(CASE WHEN c.relkind IN ('r','m') AND c.reltuples>=0 THEN FLOOR(c.reltuples)::numeric END)::text AS estimated_rows,
 COUNT(*) FILTER(WHERE c.relkind IN ('p','f') OR c.relkind IN ('r','m') AND c.reltuples<0)::text AS estimated_rows_unknown,
 SUM(CASE WHEN c.relkind IN ('r','m') THEN pg_catalog.pg_table_size(c.oid)::numeric END)::text AS data_bytes,
 SUM(CASE WHEN c.relkind IN ('r','m') THEN pg_catalog.pg_indexes_size(c.oid)::numeric END)::text AS index_bytes,
 SUM(CASE WHEN c.relkind IN ('r','m') THEN pg_catalog.pg_total_relation_size(c.oid)::numeric END)::text AS total_bytes,
 COUNT(*) FILTER(WHERE c.relkind IN ('p','f'))::text AS data_bytes_unknown,
 COUNT(*) FILTER(WHERE c.relkind IN ('p','f'))::text AS index_bytes_unknown,
 COUNT(*) FILTER(WHERE c.relkind IN ('p','f'))::text AS total_bytes_unknown` + overviewPGFrom + ` AND c.relkind IN ('r','p','m','f','v')`, []any{r.Database, r.Schema}
		}
		return `SELECT CAST(COALESCE(SUM(t.TABLE_TYPE='BASE TABLE'),0) AS CHAR) AS tables, CAST(COALESCE(SUM(t.TABLE_TYPE='VIEW'),0) AS CHAR) AS views,
 CAST(SUM(t.TABLE_ROWS) AS CHAR) AS estimated_rows, CAST(COALESCE(SUM(t.TABLE_TYPE='BASE TABLE' AND t.TABLE_ROWS IS NULL),0) AS CHAR) AS estimated_rows_unknown,
 CAST(SUM(t.DATA_LENGTH) AS CHAR) AS data_bytes, CAST(SUM(t.INDEX_LENGTH) AS CHAR) AS index_bytes, CAST(SUM(t.DATA_LENGTH+t.INDEX_LENGTH) AS CHAR) AS total_bytes,
 CAST(COALESCE(SUM(t.TABLE_TYPE='BASE TABLE' AND t.DATA_LENGTH IS NULL),0) AS CHAR) AS data_bytes_unknown,
 CAST(COALESCE(SUM(t.TABLE_TYPE='BASE TABLE' AND t.INDEX_LENGTH IS NULL),0) AS CHAR) AS index_bytes_unknown,
 CAST(COALESCE(SUM(t.TABLE_TYPE='BASE TABLE' AND (t.DATA_LENGTH IS NULL OR t.INDEX_LENGTH IS NULL)),0) AS CHAR) AS total_bytes_unknown` + overviewMyFrom, []any{r.Database}
	}
	if r.Section == "indexes" {
		if d == PostgreSQL {
			return `SELECT pg_catalog.current_database(),n.nspname,c.relname,CASE WHEN c.relkind='m' THEN 'view' ELSE 'table' END,CASE c.relkind WHEN 'm' THEN 'materialized_view' WHEN 'p' THEN 'partitioned_table' ELSE 'table' END,
 ix.relname,i.indisunique::text,am.amname,CASE WHEN pg_catalog.has_table_privilege(c.oid,'SELECT,INSERT,UPDATE,REFERENCES') THEN LEFT(pg_catalog.pg_get_indexdef(ix.oid),16385) END,NULL::text,NULL::text,'0',NULL::text,NULL::text
 FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class ix ON ix.oid=i.indexrelid JOIN pg_catalog.pg_am am ON am.oid=ix.relam` + strings.Replace(overviewPGFrom, " FROM pg_catalog.pg_class c", " JOIN pg_catalog.pg_class c ON c.oid=i.indrelid", 1) + `
 AND (c.relname ILIKE $3 ESCAPE '!' OR ix.relname ILIKE $3 ESCAPE '!')
 AND ($4='' OR (n.nspname COLLATE pg_catalog."C",c.relname COLLATE pg_catalog."C",ix.relname COLLATE pg_catalog."C") ` + op + ` ($4 COLLATE pg_catalog."C",$5 COLLATE pg_catalog."C",$6 COLLATE pg_catalog."C"))
 ORDER BY n.nspname COLLATE pg_catalog."C" ` + order + `,c.relname COLLATE pg_catalog."C" ` + order + `,ix.relname COLLATE pg_catalog."C" ` + order + ` LIMIT $7`, []any{r.Database, r.Schema, catalogSearch(r.Query), r.AfterSchema, r.AfterTable, r.AfterName, catalogLimit(r.Limit) + 1}
		}
		return `SELECT s.TABLE_SCHEMA,s.TABLE_SCHEMA,s.TABLE_NAME,'table','table',s.INDEX_NAME,IF(s.NON_UNIQUE=0,'true','false'),s.INDEX_TYPE,NULL,s.COLUMN_NAME,LEFT(s.EXPRESSION,16385),CAST(s.SEQ_IN_INDEX AS CHAR),s.COLLATION,CAST(s.SUB_PART AS CHAR)
 FROM information_schema.STATISTICS s WHERE BINARY s.TABLE_SCHEMA=BINARY ? AND (s.TABLE_NAME LIKE ? ESCAPE '!' OR s.INDEX_NAME LIKE ? ESCAPE '!')
 AND (?='' OR (BINARY s.TABLE_SCHEMA,BINARY s.TABLE_NAME,BINARY s.INDEX_NAME,s.SEQ_IN_INDEX) ` + op + ` (BINARY ?,BINARY ?,BINARY ?,?))
 ORDER BY BINARY s.TABLE_SCHEMA ` + order + `,BINARY s.TABLE_NAME ` + order + `,BINARY s.INDEX_NAME ` + order + `,s.SEQ_IN_INDEX ` + order + ` LIMIT ?`, []any{r.Database, catalogSearch(r.Query), catalogSearch(r.Query), r.AfterSchema, r.AfterSchema, r.AfterTable, r.AfterName, r.AfterOrdinal, catalogLimit(r.Limit) + 1}
	}
	if r.Section != "tables" && r.Section != "views" {
		return "", nil
	}
	if d == PostgreSQL {
		kinds := "('r','p','f')"
		if r.Section == "views" {
			kinds = "('v','m')"
		}
		return `SELECT pg_catalog.current_database(),n.nspname,c.relname,CASE WHEN c.relkind IN ('v','m') THEN 'view' ELSE 'table' END,
 CASE c.relkind WHEN 'm' THEN 'materialized_view' WHEN 'f' THEN 'foreign_table' WHEN 'p' THEN 'partitioned_table' WHEN 'v' THEN 'view' ELSE 'table' END,
 LEFT(pg_catalog.obj_description(c.oid,'pg_class'),4097),
 CASE WHEN c.relkind IN ('r','m') AND c.reltuples>=0 THEN FLOOR(c.reltuples)::numeric::text END,
 CASE WHEN c.relkind IN ('r','m') THEN pg_catalog.pg_table_size(c.oid)::text END,
 CASE WHEN c.relkind IN ('r','m') THEN pg_catalog.pg_indexes_size(c.oid)::text END,
 CASE WHEN c.relkind IN ('r','m') THEN pg_catalog.pg_total_relation_size(c.oid)::text END,
 GREATEST(st.last_analyze,st.last_autoanalyze)::text, NULL::text,NULL::text,NULL::text` + strings.Replace(overviewPGFrom, "\n WHERE", " LEFT JOIN pg_catalog.pg_stat_all_tables st ON st.relid=c.oid\n WHERE", 1) + ` AND c.relkind IN ` + kinds + `
 AND c.relname ILIKE $3 ESCAPE '!' AND ($4='' OR (n.nspname COLLATE pg_catalog."C",c.relname COLLATE pg_catalog."C") ` + op + ` ($4 COLLATE pg_catalog."C",$5 COLLATE pg_catalog."C"))
 ORDER BY n.nspname COLLATE pg_catalog."C" ` + order + `,c.relname COLLATE pg_catalog."C" ` + order + ` LIMIT $6`, []any{r.Database, r.Schema, catalogSearch(r.Query), r.AfterSchema, r.AfterName, catalogLimit(r.Limit) + 1}
	}
	kind := "BASE TABLE"
	if r.Section == "views" {
		kind = "VIEW"
	}
	return `SELECT t.TABLE_SCHEMA,t.TABLE_SCHEMA,t.TABLE_NAME,IF(t.TABLE_TYPE='VIEW','view','table'),IF(t.TABLE_TYPE='VIEW','view','table'),LEFT(t.TABLE_COMMENT,4097),
 CAST(t.TABLE_ROWS AS CHAR),CAST(t.DATA_LENGTH AS CHAR),CAST(t.INDEX_LENGTH AS CHAR),CAST(t.DATA_LENGTH+t.INDEX_LENGTH AS CHAR),NULL,t.ENGINE,c.CHARACTER_SET_NAME,t.TABLE_COLLATION
 FROM information_schema.TABLES t LEFT JOIN information_schema.COLLATION_CHARACTER_SET_APPLICABILITY c ON c.COLLATION_NAME=t.TABLE_COLLATION
 WHERE BINARY t.TABLE_SCHEMA=BINARY ? AND t.TABLE_TYPE=? AND t.TABLE_NAME LIKE ? ESCAPE '!'
 AND (?='' OR (BINARY t.TABLE_SCHEMA,BINARY t.TABLE_NAME) ` + op + ` (BINARY ?,BINARY ?)) ORDER BY BINARY t.TABLE_SCHEMA ` + order + `,BINARY t.TABLE_NAME ` + order + ` LIMIT ?`, []any{r.Database, kind, catalogSearch(r.Query), r.AfterSchema, r.AfterSchema, r.AfterName, catalogLimit(r.Limit) + 1}
}
