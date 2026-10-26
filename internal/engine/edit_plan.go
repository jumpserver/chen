package engine

import (
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jumpserver/chen/internal/sqlservice"
)

var editNumber = regexp.MustCompile(`^[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?$`)
var editInteger = regexp.MustCompile(`^[+-]?[0-9]+$`)

// Only values are parameters. All identifiers and original predicates originate
// from authenticated result provenance plus the current server catalog.
func buildEditPlan(dialect, cid string, r *Result, x *editResolved, req editPreviewRequest) (*editPlan, error) {
	if len(req.Changes) == 0 || len(req.Changes) > 500 {
		return nil, errors.New("A save must contain between 1 and 500 row changes")
	}
	if req.Source == "csv" && x.view.Mode != "table" {
		return nil, errors.New("CSV import requires a table browse result")
	}
	p := &editPlan{id: newID("edit_"), consoleID: cid, resultID: r.ID, source: req.Source, count: len(req.Changes), expires: time.Now().Add(15 * time.Minute)}
	p.schemaFingerprint = editSchemaFingerprint(x)
	refs := map[string]int{}
	for i, ref := range r.rowRefs {
		refs[ref] = i
	}
	seenRows := map[string]bool{}
	target := editQualified(x.table.Schema, x.table.Table, dialect)
	for _, change := range req.Changes {
		if change.Op != "insert" && change.Op != "update" && change.Op != "delete" {
			return nil, errors.New("Unknown row operation")
		}
		if change.Op != "update" && x.view.Mode != "table" {
			return nil, errors.New("Only table browse results support adding or deleting rows")
		}
		if req.Source == "csv" && change.Op != "insert" {
			return nil, errors.New("CSV import only appends new rows")
		}
		row := -1
		if change.Op != "insert" {
			var ok bool
			row, ok = refs[change.RowRef]
			if !ok || seenRows[change.RowRef] {
				return nil, errors.New("A row reference is invalid or appears more than once")
			}
			seenRows[change.RowRef] = true
			if r.original[row][x.pk] == nil {
				return nil, errors.New("A primary key value is unavailable")
			}
		} else if change.RowRef != "" {
			return nil, errors.New("New rows must not have an existing row reference")
		}
		if change.Op == "delete" && len(change.Values) > 0 {
			return nil, errors.New("Deleting a row does not accept new column values")
		}
		if change.Op == "update" && len(change.Values) == 0 {
			return nil, errors.New("An update must change at least one column")
		}
		indexes := []int{}
		for key := range change.Values {
			i, err := strconv.Atoi(key)
			if err != nil || strconv.Itoa(i) != key || i < 0 || i >= len(x.columns) {
				return nil, errors.New("A column index is invalid")
			}
			indexes = append(indexes, i)
		}
		sort.Ints(indexes)
		cmd := editCommand{op: change.Op}
		assign, policyAssign, previewAssign := []string{}, []string{}, []string{}
		colNames, placeholders, policyValues, previewValues := []string{}, []string{}, []string{}, []string{}
		for _, index := range indexes {
			field, cv := x.columns[index], x.view.Columns[index]
			if change.Op == "update" && !cv.Editable || change.Op == "insert" && !cv.Insertable {
				return nil, fmt.Errorf("Column %s is read-only for this operation", cv.Name)
			}
			value, err := convertEditValue(field, change.Values[strconv.Itoa(index)])
			if err != nil {
				return nil, fmt.Errorf("Column %s: %s", cv.Name, err)
			}
			name := quoteDatabaseIdentifier(field.Name, dialect)
			cmd.args = append(cmd.args, value)
			placeholder := editPlaceholder(dialect, len(cmd.args))
			assign = append(assign, name+" = "+placeholder)
			policyAssign = append(policyAssign, name+" = "+editMaskSQL(dialect, value, false))
			previewAssign = append(previewAssign, name+" = "+editMaskSQL(dialect, value, false))
			colNames = append(colNames, name)
			placeholders = append(placeholders, placeholder)
			policyValues = append(policyValues, editMaskSQL(dialect, value, false))
			previewValues = append(previewValues, editMaskSQL(dialect, value, false))
		}
		if change.Op == "insert" {
			for i, field := range x.columns {
				if _, present := change.Values[strconv.Itoa(i)]; !present && !field.Nullable && !field.Generated && !field.Identity && !field.HasDefault {
					return nil, fmt.Errorf("Column %s requires a value", x.view.Columns[i].Name)
				}
			}
			if len(indexes) == 0 {
				cmd.sql = "INSERT INTO " + target + " DEFAULT VALUES"
				if dialect == "mysql" || dialect == "mariadb" || dialect == "oceanbase-mysql" {
					cmd.sql = "INSERT INTO " + target + " () VALUES ()"
				}
				if dialect == "oracle" || dialect == "dameng" || dialect == "oceanbase-oracle" {
					return nil, errors.New("Provide at least one writable column for this database")
				}
				cmd.policySQL, cmd.previewSQL = cmd.sql, cmd.sql
			} else {
				prefix := "INSERT INTO " + target + " (" + strings.Join(colNames, ", ") + ") VALUES ("
				cmd.sql = prefix + strings.Join(placeholders, ", ") + ")"
				cmd.policySQL = prefix + strings.Join(policyValues, ", ") + ")"
				cmd.previewSQL = prefix + strings.Join(previewValues, ", ") + ")"
			}
			p.inserts++
		} else {
			where, policyWhere, previewWhere := []string{}, []string{}, []string{}
			compare := []int{x.pk}
			if change.Op == "update" {
				compare = append(compare, indexes...)
			} else {
				for i, col := range x.columns {
					if i != x.pk && col.Supported {
						compare = append(compare, i)
					}
				}
			}
			for _, index := range compare {
				field := x.columns[index]
				name := quoteDatabaseIdentifier(field.Name, dialect)
				value := r.original[row][index]
				if value == nil {
					where = append(where, name+" IS NULL")
					policyWhere = append(policyWhere, name+" IS NULL")
					previewWhere = append(previewWhere, name+" IS NULL")
					continue
				}
				// PostgreSQL json (unlike jsonb) has no equality operator. Comparing
				// its unchanged textual representation retains optimistic protection.
				if dialect == "postgresql" && strings.EqualFold(field.DataType, "json") {
					name = "CAST(" + name + " AS text)"
				}
				cmd.args = append(cmd.args, value)
				where = append(where, name+" = "+editPlaceholder(dialect, len(cmd.args)))
				policyWhere = append(policyWhere, name+" = "+editMaskSQL(dialect, value, false))
				masked := row < len(r.masked) && index < len(r.masked[row]) && r.masked[row][index]
				previewWhere = append(previewWhere, name+" = "+editMaskSQL(dialect, value, masked))
			}
			if change.Op == "update" {
				cmd.sql = "UPDATE " + target + " SET " + strings.Join(assign, ", ")
				cmd.policySQL = "UPDATE " + target + " SET " + strings.Join(policyAssign, ", ")
				cmd.previewSQL = "UPDATE " + target + " SET " + strings.Join(previewAssign, ", ")
				p.updates++
			} else {
				cmd.sql = "DELETE FROM " + target
				cmd.policySQL = cmd.sql
				cmd.previewSQL = cmd.sql
				p.deletes++
			}
			cmd.sql += " WHERE " + strings.Join(where, " AND ")
			cmd.policySQL += " WHERE " + strings.Join(policyWhere, " AND ")
			cmd.previewSQL += " WHERE " + strings.Join(previewWhere, " AND ")
		}
		p.commands = append(p.commands, cmd)
	}
	lines := []string{}
	for _, cmd := range p.commands {
		lines = append(lines, cmd.previewSQL+";")
	}
	p.previewSQL = strings.Join(lines, "\n")
	if len(p.previewSQL) > sqlservice.MaxDocumentBytes {
		return nil, errors.New("The edit batch exceeds 2 MiB; save fewer changes")
	}
	return p, nil
}
func convertEditValue(field sqlservice.EditColumn, value *string) (any, error) {
	if value == nil {
		if !field.Nullable {
			return nil, errors.New("NULL is not allowed")
		}
		return nil, nil
	}
	if !utf8.ValidString(*value) || strings.ContainsRune(*value, 0) {
		return nil, errors.New("Value must be valid text without NUL")
	}
	t := strings.ToUpper(field.DataType)
	v := *value
	if strings.Contains(t, "BOOL") || t == "BIT" {
		switch strings.ToLower(strings.TrimSpace(v)) {
		case "true", "1":
			return true, nil
		case "false", "0":
			return false, nil
		default:
			return nil, errors.New("Enter true, false, 1 or 0")
		}
	}
	if strings.Contains(t, "INT") && !strings.Contains(t, "INTERVAL") {
		if !editInteger.MatchString(v) {
			return nil, errors.New("Enter a whole number without separators")
		}
	}
	if strings.Contains(t, "DECIMAL") || strings.Contains(t, "NUMERIC") || strings.Contains(t, "NUMBER") || strings.Contains(t, "FLOAT") || strings.Contains(t, "DOUBLE") || t == "REAL" {
		if !editNumber.MatchString(v) {
			return nil, errors.New("Enter a valid number without separators")
		}
	}
	if strings.Contains(t, "JSON") && !json.Valid([]byte(v)) {
		return nil, errors.New("Enter valid JSON")
	}
	return v, nil
}
