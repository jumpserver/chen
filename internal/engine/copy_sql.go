package engine

import (
	"errors"
	"fmt"
	"net/http"
	"strings"

	"github.com/jumpserver/chen/internal/sqlservice"
)

// Only cache positions cross the trust boundary. Table names, source columns,
// keys and values all come from the authenticated result and live metadata.
type copySQLRequest struct {
	Operation string `json:"operation"`
	Rows      []int  `json:"rows"`
	Columns   []int  `json:"columns"`
}
type copiedSQL struct {
	SQL      string   `json:"sql"`
	RowCount int      `json:"row_count"`
	Notes    []string `json:"notes"`
}

func (v *Server) copyResultSQL(w http.ResponseWriter, req *http.Request) {
	s, c, ctx, done := v.editConsole(w, req)
	if s == nil {
		return
	}
	defer done()
	if !s.grant.Permissions.Copy {
		fail(w, 403, "copy_permission_denied", "Copying is not permitted")
		return
	}
	var input copySQLRequest
	if !decode(w, req, &input) {
		return
	}
	if err := validateCopySelection(input); err != nil {
		fail(w, 400, "invalid_copy_selection", err.Error())
		return
	}
	dialect := s.grant.Config.Dialect
	switch dialect {
	case "postgresql", "mysql", "mariadb", "oceanbase-mysql", "sqlserver", "oracle":
	default:
		fail(w, 409, "copy_sql_unavailable", "SQL copying is not supported for dialect "+dialect+"; DM and OB Oracle require separate literal codecs")
		return
	}
	if v.auditCatalog(ctx, s, c, "copy-"+input.Operation+"-sql") != nil {
		fail(w, 503, "audit_persistence_failed", "SQL copying could not be audited")
		return
	}
	result, err := v.editResult(s, c, req.PathValue("rid"))
	if err != nil {
		fail(w, 409, "copy_sql_unavailable", err.Error())
		return
	}
	x, err := v.resolveResultSource(ctx, s, c, result)
	if err != nil {
		fail(w, 409, "copy_sql_unavailable", err.Error())
		return
	}
	if input.Operation == "insert" {
		if dialect == "postgresql" {
			// Column grants can hide required columns from the visible catalog.
			// Count physical user columns without returning hidden names/values.
			var count int
			err = c.conn.QueryRowContext(ctx, `SELECT COUNT(*) FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class t ON t.oid=a.attrelid JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname=$1 AND t.relname=$2 AND a.attnum>0 AND NOT a.attisdropped`, x.table.Schema, x.table.Table).Scan(&count)
			if err != nil || count != len(x.table.Columns) {
				fail(w, 409, "copy_sql_unavailable", "INSERT SQL requires complete table column metadata; some columns are hidden or unavailable")
				return
			}
		}
		if (dialect == "mysql" || dialect == "mariadb" || dialect == "oceanbase-mysql") && result.browse == nil && (result.editSource == nil || !result.editSource.Star) {
			// ponytail: MySQL catalog column grants can hide trailing required
			// columns. A successful SELECT * plus the common exact count/label
			// check proves completeness; projected queries need a privilege-aware
			// completeness contract before INSERT copying can be enabled.
			fail(w, 409, "copy_sql_unavailable", "MySQL INSERT SQL requires a full-table SELECT * or table browse result to prove complete column metadata")
			return
		}
	}
	// Recheck both historical and current policy. A permission to copy visible
	// cells never grants access to a masked original, including a masked key.
	checked := map[int]bool{}
	check := func(index, row int) error {
		if index < 0 || index >= len(x.columns) || row < 0 || row >= len(result.Rows) || len(result.original[row]) != len(x.columns) || row >= len(result.masked) || len(result.masked[row]) != len(x.columns) {
			return errors.New("The selected cached cells are unavailable; refresh first")
		}
		if row < len(result.copyLossy) && index < len(result.copyLossy[row]) && result.copyLossy[row][index] {
			return fmt.Errorf("Column %s contains driver bytes without a reliable text encoding", result.Columns[index].Name)
		}
		_, masked := v.provider.Mask(s.grant, x.columns[index].Name, result.Columns[index].Name, result.original[row][index])
		if masked || result.masked[row][index] {
			return fmt.Errorf("Column %s is masked and cannot be copied as writable SQL", result.Columns[index].Name)
		}
		return nil
	}
	for _, index := range input.Columns {
		if index < len(x.columns) && (x.columns[index].Generated || x.columns[index].Identity || input.Operation == "update" && x.columns[index].PrimaryKey) {
			continue
		}
		checked[index] = true
	}
	if input.Operation == "update" {
		for _, name := range x.table.PrimaryKey {
			for i, col := range x.columns {
				if col.Name == name {
					checked[i] = true
				}
			}
		}
	}
	for _, row := range input.Rows {
		for index := range checked {
			if err = check(index, row); err != nil {
				fail(w, 409, "copy_sql_unavailable", err.Error())
				return
			}
		}
	}
	output, err := buildCopiedSQL(dialect, result, x, input)
	if err != nil {
		fail(w, 409, "copy_sql_unavailable", err.Error())
		return
	}
	if ctx.Err() != nil {
		fail(w, 409, "copy_sql_unavailable", "SQL copying timed out; select fewer cells")
		return
	}
	if s.grant.Check() != nil {
		fail(w, 409, "session_unavailable", "Session is unavailable")
		return
	}
	writeJSON(w, 200, output)
}
func validateCopySelection(req copySQLRequest) error {
	if req.Operation != "insert" && req.Operation != "update" {
		return errors.New("Choose INSERT or UPDATE SQL")
	}
	if len(req.Rows) == 0 || len(req.Rows) > 500 || len(req.Columns) == 0 || len(req.Columns) > sqlservice.MaxCatalogPageSize {
		return errors.New("Select 1 to 500 cached rows and at least one column")
	}
	for _, values := range [][]int{req.Rows, req.Columns} {
		seen := map[int]bool{}
		for _, index := range values {
			if index < 0 || seen[index] {
				return errors.New("Selection positions must be nonnegative and unique")
			}
			seen[index] = true
		}
	}
	return nil
}
func buildCopiedSQL(dialect string, r *Result, x *editResolved, req copySQLRequest) (copiedSQL, error) {
	out := copiedSQL{RowCount: len(req.Rows), Notes: []string{}}
	if err := validateCopySelection(req); err != nil {
		return out, err
	}
	indexes := []int{}
	present := map[string]bool{}
	for _, index := range req.Columns {
		if index >= len(x.columns) {
			return out, errors.New("A selected column is unavailable")
		}
		field := x.columns[index]
		if field.Generated || field.Identity {
			out.Notes = append(out.Notes, fmt.Sprintf("Generated/identity column %s is omitted", field.Name))
			continue
		}
		if req.Operation == "update" && field.PrimaryKey {
			out.Notes = append(out.Notes, fmt.Sprintf("Primary key %s is used only in WHERE", field.Name))
			continue
		}
		indexes = append(indexes, index)
		present[field.Name] = true
	}
	if len(indexes) == 0 {
		return out, errors.New("Select at least one writable, non-generated column")
	}
	keys := []int{}
	if req.Operation == "insert" {
		// Omitted defaults are evaluated by the target database, never guessed from
		// a cached value or copied as the text of a default expression.
		for _, field := range x.table.Columns {
			if present[field.Name] || field.Generated || field.Identity {
				continue
			}
			if !field.Nullable && !field.HasDefault {
				return out, fmt.Errorf("Select required column %s to generate INSERT SQL", field.Name)
			}
			if field.HasDefault {
				out.Notes = append(out.Notes, fmt.Sprintf("Omitted column %s uses its database default", field.Name))
			}
		}
	} else {
		if len(x.table.PrimaryKey) == 0 {
			return out, errors.New("UPDATE SQL requires a complete primary key")
		}
		for _, name := range x.table.PrimaryKey {
			found := -1
			for i, field := range x.columns {
				if field.Name == name {
					found = i
					break
				}
			}
			if found < 0 {
				return out, fmt.Errorf("UPDATE SQL requires primary-key column %s in the result", name)
			}
			keys = append(keys, found)
		}
	}
	target := editQualified(x.table.Schema, x.table.Table, dialect)
	var sql strings.Builder
	for _, row := range req.Rows {
		if row >= len(r.original) || len(r.original[row]) != len(x.columns) {
			return out, errors.New("A selected cached row is unavailable")
		}
		values := []string{}
		names := []string{}
		assignments := []string{}
		literal := func(index int) (string, error) {
			text, err := copySQLLiteral(dialect, x.columns[index].DataType, r.original[row][index])
			if err != nil {
				return "", fmt.Errorf("Row %d, column %s: %w", row+1, x.columns[index].Name, err)
			}
			return text, nil
		}
		for _, index := range indexes {
			if r.original[row][index] == nil && !x.columns[index].Nullable {
				return out, fmt.Errorf("Column %s no longer permits NULL; refresh the result", x.columns[index].Name)
			}
			value, err := literal(index)
			if err != nil {
				return out, err
			}
			name := quoteDatabaseIdentifier(x.columns[index].Name, dialect)
			names = append(names, name)
			values = append(values, value)
			assignments = append(assignments, name+" = "+value)
		}
		line := ""
		if req.Operation == "insert" {
			line = "INSERT INTO " + target + " (" + strings.Join(names, ", ") + ") VALUES (" + strings.Join(values, ", ") + ");\n"
		} else {
			where := []string{}
			for _, index := range keys {
				if r.original[row][index] == nil {
					return out, errors.New("A primary key value is NULL or unavailable")
				}
				value, err := literal(index)
				if err != nil {
					return out, err
				}
				where = append(where, quoteDatabaseIdentifier(x.columns[index].Name, dialect)+" = "+value)
			}
			// Never emit an UPDATE unless every key was found and serialized.
			if len(where) == 0 || len(where) != len(x.table.PrimaryKey) {
				return out, errors.New("UPDATE SQL requires a complete primary key")
			}
			line = "UPDATE " + target + " SET " + strings.Join(assignments, ", ") + " WHERE " + strings.Join(where, " AND ") + ";\n"
		}
		if sql.Len()+len(line) > sqlservice.MaxDocumentBytes {
			return out, errors.New("Copied SQL exceeds the 2 MiB limit; select fewer cells")
		}
		sql.WriteString(line)
	}
	out.SQL = strings.TrimSuffix(sql.String(), "\n")
	return out, nil
}
