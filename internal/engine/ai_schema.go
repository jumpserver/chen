package engine

import (
	"errors"
	"sort"
	"strings"

	"github.com/jumpserver/chen/internal/sqlservice"
)

func aiSchemaResult(req *catalogRequest, e aiEditorContext, tool, query string, tables []string, limit int, objects []sqlservice.CatalogObject) (any, error) {
	d := sqlservice.Dialect(e.Dialect)
	if tool == "search_schema" {
		if limit == 0 {
			limit = 8
		}
		terms := strings.Fields(strings.ToLower(query))
		if len(terms) > 16 {
			terms = terms[:16]
		}
		type candidate struct {
			Database string   `json:"database"`
			Schema   string   `json:"schema"`
			Name     string   `json:"name"`
			Kind     string   `json:"kind"`
			Score    int      `json:"score"`
			Matches  []string `json:"matches"`
		}
		out := []candidate{}
		for i, o := range objects {
			if i >= 16 {
				break
			}
			score := 0
			matches := []string{}
			for _, term := range terms {
				if strings.Contains(strings.ToLower(o.Name), term) {
					score += 10
					matches = append(matches, "table_name")
				}
				if strings.Contains(strings.ToLower(o.Comment), term) {
					score += 4
					matches = append(matches, "table_comment")
				}
			}
			columns, err := sqlservice.ColumnRows(req.ctx, req.conn, d, sqlservice.CatalogColumnRequest{Database: req.database, Schema: o.Schema, Table: o.Name, Limit: 96})
			if err != nil {
				return nil, errors.New("Unable to search authorized columns")
			}
			for _, c := range columns {
				for _, term := range terms {
					if strings.Contains(strings.ToLower(c.Name), term) {
						score += 2
						matches = append(matches, "column_name")
					}
					if strings.Contains(strings.ToLower(c.Comment), term) {
						score++
						matches = append(matches, "column_comment")
					}
				}
			}
			if score > 0 {
				seen := map[string]bool{}
				unique := []string{}
				for _, match := range matches {
					if !seen[match] {
						seen[match] = true
						unique = append(unique, match)
					}
				}
				out = append(out, candidate{o.Database, o.Schema, o.Name, o.Kind, score, unique})
			}
		}
		sort.SliceStable(out, func(i, j int) bool { return out[i].Score > out[j].Score })
		truncated := len(objects) > 16 || len(out) > limit
		if len(out) > limit {
			out = out[:limit]
		}
		return map[string]any{"kind": "schema_search", "tables": out, "truncated": truncated, "scope": map[string]string{"database": req.database, "schema": e.Schema}, "business_rows_read": false}, nil
	}
	wanted := map[string]bool{}
	for _, table := range tables {
		wanted[table] = true
	}
	chosen := []sqlservice.CatalogObject{}
	for _, o := range objects {
		if wanted[o.Name] || len(tables) == 0 && (query == "*" || strings.Contains(strings.ToLower(o.Name), strings.ToLower(query))) {
			chosen = append(chosen, o)
			delete(wanted, o.Name)
		}
	}
	for table := range wanted {
		matches, err := sqlservice.ObjectRows(req.ctx, req.conn, d, sqlservice.CatalogObjectRequest{Database: req.database, Schema: e.Schema, Query: table, Limit: 100})
		if err != nil {
			return nil, errors.New("Unable to inspect authorized schema")
		}
		for _, o := range matches {
			if o.Name == table {
				chosen = append(chosen, o)
				delete(wanted, table)
				break
			}
		}
	}
	if len(wanted) > 0 {
		return nil, errors.New("A requested table is unavailable in the verified schema")
	}
	truncated := len(chosen) > 8 || len(objects) > 100
	if len(chosen) > 8 {
		chosen = chosen[:8]
	}
	inspections := []sqlservice.TableInspection{}
	for _, o := range chosen {
		item, err := sqlservice.InspectTableSummary(req.ctx, req.conn, d, sqlservice.CatalogColumnRequest{Database: req.database, Schema: o.Schema, Table: o.Name})
		if err != nil {
			return nil, errors.New("Unable to inspect authorized table metadata")
		}
		delete(item.Sections, "ddl")
		if len(item.Columns) > 96 {
			item.Columns = item.Columns[:96]
			item.Truncated = true
		}
		for name, section := range item.Sections {
			if len(section.Items) > 48 {
				section.Items = section.Items[:48]
				section.Truncated = true
				item.Sections[name] = section
			}
		}
		inspections = append(inspections, item)
	}
	return map[string]any{"kind": "schema_inspection", "tables": inspections, "truncated": truncated, "scope": map[string]string{"database": req.database, "schema": e.Schema}, "business_rows_read": false}, nil
}
