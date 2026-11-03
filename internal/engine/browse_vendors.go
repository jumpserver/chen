package engine

import (
	"fmt"
	"github.com/jumpserver/chen/internal/sqlservice"
	"strings"
)

func browsePolicyLiteral(d, text string) (string, error) {
	if d == "oracle" && text == "" {
		return "''", nil
	}
	if d == "clickhouse" {
		return "'" + strings.ReplaceAll(strings.ReplaceAll(text, "\\", "\\\\"), "'", "\\'") + "'", nil
	}
	if d == "db2" {
		return "'" + strings.ReplaceAll(text, "'", "''") + "'", nil
	}
	return copyTextLiteral(d, text)
}

// The base/order arguments are built from catalog-validated columns above,
// never found by deleting text from a user's query. Explicit projection removes
// the synthetic row number, including when a real column has the same name.
func browsePageSQL(d, full, base, order string, columns []sqlservice.EditColumn, offset, size int) string {
	if d != "oracle" && d != "oceanbase-oracle" && d != "sqlserver" && d != "db2" {
		return fmt.Sprintf("%s LIMIT %d OFFSET %d", full, size, offset)
	}
	used := map[string]bool{}
	projection := make([]string, len(columns))
	for i, c := range columns {
		used[strings.ToLower(c.Name)] = true
		projection[i] = quoteDatabaseIdentifier(c.Name, d)
	}
	alias := "CHEN_row_number"
	for i := 1; used[strings.ToLower(alias)]; i++ {
		alias = fmt.Sprintf("CHEN_row_number_%d", i)
	}
	rn := quoteDatabaseIdentifier(alias, d)
	if d == "oracle" || d == "oceanbase-oracle" {
		return fmt.Sprintf("SELECT %s FROM (SELECT CHEN_page.*, ROWNUM %s FROM (\n%s\n) CHEN_page WHERE ROWNUM<=%d) WHERE %s>%d ORDER BY %s", strings.Join(projection, ","), rn, full, offset+size, rn, offset, rn)
	}
	window := ""
	if order != "" {
		window = "ORDER BY " + order
	} else if d == "sqlserver" {
		window = "ORDER BY (SELECT NULL)"
	}
	// ROW_NUMBER is available before OFFSET/FETCH (SQL Server 2005+, DB2 9.7+).
	// No synthetic field reaches the result or its row references.
	return fmt.Sprintf("SELECT %s FROM (SELECT CHEN_source.*, ROW_NUMBER() OVER (%s) AS %s FROM (\n%s\n) CHEN_source) AS CHEN_page WHERE %s>%d AND %s<=%d ORDER BY %s", strings.Join(projection, ","), window, rn, base, rn, offset, rn, offset+size, rn)
}
