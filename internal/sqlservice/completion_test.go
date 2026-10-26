package sqlservice

import (
	"context"
	"strings"
	"testing"
)

type fixtureCatalog struct {
	calls         []ColumnRequest
	relations     []Relation
	relationCalls []MetadataRequest
}

func (f *fixtureCatalog) Relations(_ context.Context, r MetadataRequest) ([]Relation, error) {
	f.relationCalls = append(f.relationCalls, r)
	if f.relations != nil {
		return f.relations, nil
	}
	return []Relation{{Schema: "public", Name: "users", Kind: "BASE TABLE"}, {Schema: "public", Name: "orders", Kind: "BASE TABLE"}}, nil
}
func (f *fixtureCatalog) Columns(_ context.Context, r ColumnRequest) ([]Column, error) {
	f.calls = append(f.calls, r)
	switch r.Table {
	case "users":
		return []Column{{Name: "id", DataType: "bigint"}, {Name: "name", DataType: "text", Comment: "display name"}}, nil
	case "orders":
		return []Column{{Name: "order_id", DataType: "bigint"}, {Name: "user_id", DataType: "bigint"}}, nil
	case "用户":
		return []Column{{Name: "用户编号", DataType: "bigint"}, {Name: "姓名", DataType: "text"}}, nil
	case "Users":
		return []Column{{Name: "UserID", DataType: "bigint"}}, nil
	}
	return nil, nil
}
func completeFixture(t *testing.T, d Dialect, marked string, f *fixtureCatalog) CompletionResponse {
	t.Helper()
	at := strings.Index(marked, "|")
	if at < 0 {
		t.Fatal("missing cursor")
	}
	document := marked[:at] + marked[at+1:]
	r, e := CompletionWithCatalog(context.Background(), d, CompletionRequest{Document: document, UTF16Offset: byteToOffset(document, at), Version: 42}, f)
	if e != nil {
		t.Fatal(e)
	}
	if r.Version != 42 {
		t.Fatal("document version lost")
	}
	return r
}
func labels(r CompletionResponse) []string {
	out := []string{}
	for _, x := range r.Items {
		out = append(out, x.Label)
	}
	return out
}
func requireLabels(t *testing.T, r CompletionResponse, want ...string) {
	t.Helper()
	for _, s := range want {
		found := false
		for _, x := range r.Items {
			if x.Label == s {
				found = true
			}
		}
		if !found {
			t.Fatalf("missing %q in %v", s, labels(r))
		}
	}
}
func rejectLabels(t *testing.T, r CompletionResponse, unwanted ...string) {
	t.Helper()
	for _, s := range unwanted {
		for _, x := range r.Items {
			if x.Label == s {
				t.Fatalf("unexpected %q in %v", s, labels(r))
			}
		}
	}
}

func TestCompletionScopes(t *testing.T) {
	tests := []struct {
		name, sql    string
		want, absent []string
	}{
		{"alias after cursor", "SELECT u.| FROM users AS u", []string{"id", "name"}, []string{"order_id", "users"}},
		{"CTE projection", "WITH selected AS (SELECT id AS selected_id, name FROM users) SELECT s.| FROM selected s", []string{"selected_id", "name"}, []string{"id", "order_id"}},
		{"CTE explicit names", "WITH selected(x, y) AS (SELECT id, name FROM users) SELECT s.| FROM selected s", []string{"x", "y"}, []string{"id", "name"}},
		{"derived projection", "SELECT d.| FROM (SELECT id AS projected_id, name FROM users) d", []string{"projected_id", "name"}, []string{"id"}},
		{"CTE wildcard", "WITH selected AS (SELECT u.* FROM users u) SELECT s.| FROM selected s", []string{"id", "name"}, nil},
		{"nested shadowing", "SELECT u.id FROM users u WHERE EXISTS (SELECT u.| FROM orders u)", []string{"order_id", "user_id"}, []string{"id", "name"}},
		{"correlated lookup", "SELECT u.id FROM users u WHERE EXISTS (SELECT u.| FROM orders o)", []string{"id", "name"}, []string{"order_id"}},
		{"sibling isolation", "SELECT u.id FROM users u; SELECT o.| FROM orders o", []string{"order_id", "user_id"}, []string{"id", "name"}},
		{"string fake binding", "SELECT u.|, 'FROM orders u' FROM users u", []string{"id", "name"}, []string{"order_id"}},
		{"comment fake binding", "SELECT u.| /* FROM orders u */ FROM users u", []string{"id", "name"}, []string{"order_id"}},
		{"quoted unicode", "SELECT 用户.| FROM \"用户\" AS 用户", []string{"用户编号", "姓名"}, nil},
		{"schema qualified columns", "SELECT public.users.|", []string{"id", "name"}, nil},
		{"nested CTE inherited", "WITH x AS (SELECT id AS chosen FROM users) SELECT * FROM x WHERE EXISTS (SELECT x.|)", []string{"chosen"}, []string{"id"}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			r := completeFixture(t, PostgreSQL, tc.sql, &fixtureCatalog{})
			requireLabels(t, r, tc.want...)
			rejectLabels(t, r, tc.absent...)
		})
	}
}

func TestCompletionPrefixUTF16AndQuotedIdentifiers(t *testing.T) {
	f := &fixtureCatalog{}
	r := completeFixture(t, PostgreSQL, `SELECT '😀', u.na| FROM users u`, f)
	requireLabels(t, r, "name")
	rejectLabels(t, r, "id")
	if r.To-r.From != 2 {
		t.Fatalf("wrong replacement: %+v", r)
	}
	for _, x := range r.Items {
		if x.Label == "name" && x.Documentation != "display name" {
			t.Fatal("lost documentation")
		}
	}
	r = completeFixture(t, PostgreSQL, `SELECT "U"."Us|" FROM "Users" AS "U"`, &fixtureCatalog{})
	requireLabels(t, r, "UserID")
	if r.Items[0].InsertText != `"UserID"` {
		t.Fatalf("quoted insertion %q", r.Items[0].InsertText)
	}
	r = completeFixture(t, MySQL, "SELECT u.`na|` FROM users u", &fixtureCatalog{})
	requireLabels(t, r, "name")
	if r.Items[0].InsertText != "`name`" {
		t.Fatal(r.Items[0].InsertText)
	}
}

func TestCompletionTablesKeywordsAndNoStringHints(t *testing.T) {
	r := completeFixture(t, PostgreSQL, "SELECT * FROM us|", &fixtureCatalog{})
	requireLabels(t, r, "users")
	rejectLabels(t, r, "orders", "SELECT")
	r = completeFixture(t, PostgreSQL, "SEL|", &fixtureCatalog{})
	requireLabels(t, r, "SELECT")
	for _, q := range []string{"SELECT 'hello | world'", "SELECT $$ FROM users | $$", "SELECT 1 -- FROM users |\n", "SELECT /* FROM users | */ 1"} {
		r = completeFixture(t, PostgreSQL, q, &fixtureCatalog{})
		if len(r.Items) > 0 {
			t.Fatalf("hints inside literal/comment %q: %v", q, labels(r))
		}
	}
	r = completeFixture(t, PostgreSQL, "WITH local AS (SELECT 1 AS n) SELECT * FROM lo|", &fixtureCatalog{})
	requireLabels(t, r, "local")
}

func TestCompletionUsesOnlyReferencedCatalogAndNoSharedCache(t *testing.T) {
	first := &fixtureCatalog{}
	_ = completeFixture(t, PostgreSQL, "SELECT u.| FROM users u", first)
	if len(first.calls) != 1 || first.calls[0].Table != "users" {
		t.Fatalf("calls: %+v", first.calls)
	}
	second := &fixtureCatalog{}
	_ = completeFixture(t, PostgreSQL, "SELECT u.| FROM users u", second)
	if len(second.calls) != 1 {
		t.Fatal("metadata leaked through cross-session cache")
	}
}

func TestCompletionInvalidPositions(t *testing.T) {
	for _, offset := range []int{-1, 1, 4} {
		if _, err := CompletionWithCatalog(context.Background(), PostgreSQL, CompletionRequest{Document: "😀", UTF16Offset: offset}, nil); err == nil {
			t.Fatalf("accepted invalid UTF16 offset %d", offset)
		}
	}
}
func TestCatalogPrefixEscaping(t *testing.T) {
	if got := likePrefix("user_%!"); got != "user!_!%!!%" {
		t.Fatalf("unsafe prefix %q", got)
	}
}

func findCompletion(t *testing.T, result CompletionResponse, label string) CompletionItem {
	t.Helper()
	for _, item := range result.Items {
		if item.Label == label {
			return item
		}
	}
	t.Fatalf("missing %q in %v", label, labels(result))
	return CompletionItem{}
}

func TestCompletionClauseAccuracy(t *testing.T) {
	for _, dialect := range []Dialect{MySQL, PostgreSQL} {
		for _, statement := range []string{
			"SELECT * FROM users u, |",
			"SELECT * FROM users u JOIN | ON u.id = o.user_id",
			"SELECT * FROM users u LEFT JOIN public.or| o ON u.id = o.user_id",
		} {
			r := completeFixture(t, dialect, statement, &fixtureCatalog{})
			requireLabels(t, r, "orders")
			rejectLabels(t, r, "id", "SELECT", "COUNT", "u")
		}
		for _, statement := range []string{
			"SELECT | FROM users u", "SELECT COALESCE(u.id, |) FROM users u",
			"SELECT * FROM users u WHERE |", "SELECT * FROM users u GROUP BY |",
		} {
			r := completeFixture(t, dialect, statement, &fixtureCatalog{})
			requireLabels(t, r, "id", "name")
			rejectLabels(t, r, "users", "orders", "INSERT INTO", "UPDATE", "COMMIT")
		}
		for _, statement := range []string{
			"SELECT unknown.| FROM users u", "SELECT * FROM users AS |", "SELECT u.id AS | FROM users u",
			"SELECT 'unfinished |", "SELECT 'escaped quote''|", "SELECT /* unclosed |",
		} {
			if r := completeFixture(t, dialect, statement, &fixtureCatalog{}); len(r.Items) != 0 {
				t.Fatalf("unexpected suggestions in %q: %v", statement, labels(r))
			}
		}
	}
	for _, statement := range []string{"SELECT $$ unfinished |", "SELECT $tag$ unfinished |"} {
		if r := completeFixture(t, PostgreSQL, statement, &fixtureCatalog{}); len(r.Items) != 0 {
			t.Fatalf("suggestions at end of unclosed dollar string: %v", labels(r))
		}
	}
}

func TestCompletionJoinQualificationAndUsing(t *testing.T) {
	for _, dialect := range []Dialect{MySQL, PostgreSQL} {
		r := completeFixture(t, dialect, "SELECT i| FROM users u JOIN users v ON u.id = v.id", &fixtureCatalog{})
		requireLabels(t, r, "u.id", "v.id")
		rejectLabels(t, r, "id")
		item := findCompletion(t, r, "u.id")
		if item.InsertText != "u.id" || item.FilterText != "id" || item.SortText == "" {
			t.Fatalf("qualified insertion/filter/rank: %+v", item)
		}
		r = completeFixture(t, dialect, "SELECT * FROM users u JOIN orders o ON |", &fixtureCatalog{})
		requireLabels(t, r, "u.id", "o.user_id", "o.order_id")
		rejectLabels(t, r, "id", "user_id", "users", "orders")
		r = completeFixture(t, dialect, "SELECT * FROM users u JOIN users v USING (|)", &fixtureCatalog{})
		requireLabels(t, r, "id", "name")
		rejectLabels(t, r, "u.id", "v.id", "SELECT", "u", "users")
		r = completeFixture(t, dialect, "SELECT * FROM users u JOIN users v USING (id, |)", &fixtureCatalog{})
		requireLabels(t, r, "name")
		rejectLabels(t, r, "id")
		r = completeFixture(t, dialect, "SELECT * FROM users u JOIN orders o USING (|)", &fixtureCatalog{})
		if len(r.Items) != 0 {
			t.Fatalf("invented shared columns: %v", labels(r))
		}
		r = completeFixture(t, dialect, "SELECT * FROM users u JOIN users v ON | JOIN orders o ON o.user_id=u.id", &fixtureCatalog{})
		rejectLabels(t, r, "o", "o.user_id", "o.order_id")
	}
	r := completeFixture(t, PostgreSQL, `SELECT "UserID|" FROM "Users" AS "U" JOIN "Users" AS "V" ON true`, &fixtureCatalog{})
	if item := findCompletion(t, r, "U.UserID"); item.InsertText != `"U"."UserID"` {
		t.Fatalf("quoted qualified insertion: %+v", item)
	}
}

func TestCompletionProjectionAndSetScope(t *testing.T) {
	r := completeFixture(t, PostgreSQL, "SELECT id AS chosen FROM users u ORDER BY ch|", &fixtureCatalog{})
	requireLabels(t, r, "chosen")
	r = completeFixture(t, PostgreSQL, "SELECT id AS chosen FROM users u WHERE ch|", &fixtureCatalog{})
	rejectLabels(t, r, "chosen")
	r = completeFixture(t, PostgreSQL, "WITH x AS (SELECT id + name FROM users) SELECT x.| FROM x", &fixtureCatalog{})
	rejectLabels(t, r, "id", "name")
	for _, statement := range []string{
		"SELECT id FROM users u UNION ALL SELECT o.| FROM orders o",
		"WITH x AS (SELECT id AS chosen FROM users) SELECT id FROM users u UNION SELECT x.| FROM x",
	} {
		r = completeFixture(t, PostgreSQL, statement, &fixtureCatalog{})
		rejectLabels(t, r, "name", "id")
		if strings.Contains(statement, "o.|") {
			requireLabels(t, r, "order_id", "user_id")
		} else {
			requireLabels(t, r, "chosen")
		}
	}
	r = completeFixture(t, PostgreSQL, "SELECT u.| FROM users u UNION ALL SELECT order_id FROM orders o", &fixtureCatalog{})
	requireLabels(t, r, "id", "name")
	rejectLabels(t, r, "order_id", "user_id")
	r = completeFixture(t, PostgreSQL, "SELECT u.id FROM users u UNION ALL SELECT u.| FROM orders o", &fixtureCatalog{})
	if len(r.Items) != 0 {
		t.Fatalf("first branch alias leaked: %v", labels(r))
	}
}

func TestCompletionDoesNotScanCatalogTablesInExpressions(t *testing.T) {
	for _, statement := range []string{"SELECT | FROM users u", "SELECT unknown.| FROM users u", "SELECT * FROM users u WHERE |", "SELECT * FROM users AS |"} {
		fixture := &fixtureCatalog{}
		_ = completeFixture(t, PostgreSQL, statement, fixture)
		if len(fixture.relationCalls) != 0 {
			t.Fatalf("relation catalog queried in %q", statement)
		}
	}
	fixture := &fixtureCatalog{}
	_ = completeFixture(t, PostgreSQL, "SELECT * FROM public.us|", fixture)
	if len(fixture.relationCalls) != 1 || fixture.relationCalls[0].Context.Schema != "public" || fixture.relationCalls[0].Prefix != "us" {
		t.Fatalf("qualified catalog request: %+v", fixture.relationCalls)
	}
}

func TestCompletionUsingHasNoCorrelatedParentColumns(t *testing.T) {
	r := completeFixture(t, PostgreSQL, "SELECT 1 FROM orders parent WHERE EXISTS (SELECT 1 FROM users u JOIN users v USING (|))", &fixtureCatalog{})
	requireLabels(t, r, "id", "name")
	rejectLabels(t, r, "user_id", "order_id")
}

func TestCompletionColumnDetailsAvoidRepeatedSource(t *testing.T) {
	tests := []struct{ sql, label, detail string }{
		{"WITH x AS (SELECT 1 AS item) SELECT x.| FROM x", "item", "x"},
		{"SELECT users.| FROM users", "id", "bigint · users"},
		{"SELECT u.| FROM users u", "id", "bigint · u · users"},
	}
	for _, tc := range tests {
		r := completeFixture(t, PostgreSQL, tc.sql, &fixtureCatalog{})
		if got := findCompletion(t, r, tc.label).Detail; got != tc.detail {
			t.Fatalf("detail for %q: got %q, want %q", tc.sql, got, tc.detail)
		}
	}
}
