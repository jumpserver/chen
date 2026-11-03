package jumpserver

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/jumpserver/wisp/protobuf-go/protobuf"
)

func TestParityJavaACLPatternsAndFailureRemainEnforced(t *testing.T) {
	for _, tc := range []struct{ pattern, yes, no string }{
		{`(?i)\bDELETE\b(?=\s+FROM)`, "DELETE FROM t", "SELECT 1"},
		{`(?<=DROP\s)TABLE`, "DROP TABLE t", "SELECT 'TABLE'"},
		{`(?i)\b(\w+)\s+\1\b`, "SELECT SELECT", "SELECT id"},
		{`(?=\QDROP [x]\E)DROP`, "DROP [x]", "DROP [y]"},
	} {
		w := &testWisp{token: validToken()}
		w.token.FilterRules = []*protobuf.CommandACL{{Id: "first", Action: protobuf.CommandACL_Reject, CommandGroups: []*protobuf.CommandGroup{{Id: "group", Pattern: tc.pattern}}}, {Id: "later", Action: protobuf.CommandACL_Accept, CommandGroups: []*protobuf.CommandGroup{{Pattern: ".*"}}}}
		p := testProvider(t, w)
		g, err := p.Open(context.Background(), "synthetic-token", "192.0.2.1")
		if err != nil {
			t.Fatal(tc.pattern, err)
		}
		var policy *PolicyError
		if err = p.Authorize(context.Background(), g, tc.yes); !errors.As(err, &policy) || policy.Code != "command_rejected_by_acl" || policy.ACLID != "first" {
			t.Fatalf("pattern not enforced: %q %v", tc.pattern, err)
		}
		if err = p.Authorize(context.Background(), g, tc.no); err != nil {
			t.Fatalf("nonmatching SQL rejected: %v", err)
		}
	}
	rules := []*protobuf.CommandACL{{Id: "slow", Action: protobuf.CommandACL_Accept, CommandGroups: []*protobuf.CommandGroup{{Pattern: `(?=a)(a+)+$`}}}}
	matchers, err := compileMatchers(rules)
	if err != nil {
		t.Fatal(err)
	}
	matchers[0].advanced.MatchTimeout = time.Millisecond
	g := &Grant{matchers: matchers}
	matched := matchPolicy(g, strings.Repeat("a", 5000)+"!")
	if matched == nil || !matched.matchFailed || matched.action != protobuf.CommandACL_Reject {
		t.Fatal("timeout granted access")
	}
}
