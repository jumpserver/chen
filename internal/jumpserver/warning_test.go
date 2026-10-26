package jumpserver

import (
	"context"
	"errors"
	"testing"

	"github.com/jumpserver/wisp/protobuf-go/protobuf"
)

func TestNotifyWarningRequiresExplicitBatchConfirmation(t *testing.T) {
	for _, action := range []protobuf.CommandACL_Action{protobuf.CommandACL_Warning, protobuf.CommandACL_NotifyWarning} {
		w := &testWisp{token: validToken()}
		w.token.FilterRules = []*protobuf.CommandACL{{Id: "warning", Action: action, CommandGroups: []*protobuf.CommandGroup{{Id: "group", Pattern: "SELECT"}}}}
		p := testProvider(t, w)
		g, err := p.Open(context.Background(), "synthetic-token", "192.0.2.1")
		if err != nil {
			t.Fatal(err)
		}
		for _, confirm := range []bool{false, true} {
			decision, err := p.AuthorizeCommand(context.Background(), g, AuthorizationRequest{SQL: "SELECT 1", BatchSQL: "SELECT 1", ConfirmWarnings: confirm})
			if action == protobuf.CommandACL_NotifyWarning && !confirm {
				var pe *PolicyError
				if !errors.As(err, &pe) || pe.Code != "command_warning_confirmation_required" {
					t.Fatal("notify warning executed without confirmation")
				}
			} else if err != nil {
				t.Fatal(err)
			}
			if decision.RiskLevel != 1 || decision.ACLID != "warning" || decision.NotifyWarning != (action == protobuf.CommandACL_NotifyWarning) {
				t.Fatal("warning decision lost policy identity")
			}
		}
	}
}
