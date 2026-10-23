package jumpserver

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/jumpserver/wisp/protobuf-go/protobuf"
)

func TestACLFirstMatchAndUnsupportedInteractivePolicies(t *testing.T) {
	for _, tc := range []struct {
		action protobuf.CommandACL_Action
		code   string
		risk   protobuf.RiskLevel
	}{
		{protobuf.CommandACL_Reject, "command_rejected_by_acl", protobuf.RiskLevel_Reject},
		{protobuf.CommandACL_Review, "command_review_requires_legacy_chen", protobuf.RiskLevel_ReviewReject},
		{protobuf.CommandACL_NotifyWarning, "command_notification_requires_legacy_chen", protobuf.RiskLevel_Warning},
		{protobuf.CommandACL_Accept, "", protobuf.RiskLevel_Normal},
		{protobuf.CommandACL_Warning, "", protobuf.RiskLevel_Warning},
	} {
		t.Run(tc.action.String(), func(t *testing.T) {
			w := &testWisp{token: validToken()}
			w.token.FilterRules = []*protobuf.CommandACL{
				{Id: "first-rule", Action: tc.action, CommandGroups: []*protobuf.CommandGroup{{Id: "first-group", Pattern: `\bdelete\b`, IgnoreCase: true}}},
				{Id: "later-accept", Action: protobuf.CommandACL_Accept, CommandGroups: []*protobuf.CommandGroup{{Pattern: ".*"}}},
			}
			p := testProvider(t, w)
			g, err := p.Open(context.Background(), "synthetic-token", "192.0.2.1")
			if err != nil {
				t.Fatal(err)
			}
			sql := "DELETE FROM example WHERE id = 1"
			err = p.Authorize(context.Background(), g, sql)
			if tc.code == "" {
				if err != nil {
					t.Fatal(err)
				}
			} else {
				var policy *PolicyError
				if !errors.As(err, &policy) || policy.Code != tc.code || policy.ACLID != "first-rule" || policy.GroupID != "first-group" || policy.RiskLevel != int32(tc.risk) {
					t.Fatalf("incorrect denial identity: %v", err)
				}
			}
			if err := p.Record(context.Background(), g, AuditRecord{SQL: sql, Output: "operation completed"}); err != nil {
				t.Fatal(err)
			}
			p.flush(context.Background())
			if len(w.commands) != 1 || w.commands[0].RiskLevel != tc.risk || w.commands[0].CmdAclId != "first-rule" || w.commands[0].CmdGroupId != "first-group" {
				t.Fatal("audit lost the command policy classification")
			}
		})
	}
}

func TestDataMaskingUnicodeWildcardsScalarsAndAliasFallback(t *testing.T) {
	w := &testWisp{token: validToken()}
	w.token.DataMaskingRules = []*protobuf.DataMaskingRule{
		{FieldsPattern: "secret*, private.value", MaskingMethod: "fixed_char", MaskPattern: "REDACTED"},
		{FieldsPattern: "full_name", MaskingMethod: "hide_middle"},
		{FieldsPattern: "number", MaskingMethod: "keep_suffix"},
	}
	p := testProvider(t, w)
	g, err := p.Open(context.Background(), "synthetic-token", "192.0.2.1")
	if err != nil {
		t.Fatal(err)
	}
	tests := []struct {
		column, label string
		value, want   any
		masked        bool
	}{
		{"secret_token", "alias", "sensitive", "REDACTED", true},
		{"full_name", "full_name", "张😀三", "张*三", true},
		{"number", "number", int64(123456789), "*******89", true},
		{"private.value", "private.value", nil, "REDACTED", true},
		{"privateXvalue", "privateXvalue", "visible", "visible", false},
		{"", "untraceable_alias", "sensitive", "####", true},
	}
	for _, tc := range tests {
		got, masked := p.Mask(g, tc.column, tc.label, tc.value)
		if got != tc.want || masked != tc.masked {
			t.Fatalf("Mask(%s,%s) = %v/%v, want %v/%v", tc.column, tc.label, got, masked, tc.want, tc.masked)
		}
	}
}

func TestBusyOnlySuspendsIdleAndCompletionRestoresActivity(t *testing.T) {
	now := time.Now()
	g := &Grant{created: now, lastActive: now.Add(-2 * time.Minute), MaxIdle: time.Minute, ExpiresAt: now.Add(time.Hour)}
	if err := g.Check(); !errors.Is(err, ErrIdle) {
		t.Fatalf("idle session = %v", err)
	}
	g.SetBusy(true)
	if err := g.Check(); err != nil {
		t.Fatalf("active query was disconnected as idle: %v", err)
	}
	g.SetBusy(false)
	if err := g.Check(); err != nil {
		t.Fatalf("query completion did not count as activity: %v", err)
	}
	g.SetBusy(true)
	g.ExpiresAt = now.Add(-time.Second)
	if err := g.Check(); !errors.Is(err, ErrExpired) {
		t.Fatalf("busy session bypassed permission expiry: %v", err)
	}
}
