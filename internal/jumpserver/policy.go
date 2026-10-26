package jumpserver

import (
	"context"
	"errors"
	"fmt"
	"regexp"
	"strings"

	"github.com/jumpserver/wisp/protobuf-go/protobuf"
)

type commandMatcher struct {
	pattern *regexp.Regexp
	action  protobuf.CommandACL_Action
	aclID   string
	groupID string
}

// Preserve the Core-provided order (first matching ACL/group wins). RE2 does
// not understand every Java regular expression. Reject unsupported patterns
// while authenticating rather than silently dropping an enforcing rule.
func compileMatchers(rules []*protobuf.CommandACL) ([]commandMatcher, error) {
	var result []commandMatcher
	for _, rule := range rules {
		if rule == nil || len(rule.CommandGroups) == 0 {
			return nil, errors.New("unsupported_empty_command_acl")
		}
		if rule.Action < protobuf.CommandACL_Reject || rule.Action > protobuf.CommandACL_NotifyWarning {
			return nil, errors.New("unsupported_command_acl_action")
		}
		for _, group := range rule.CommandGroups {
			if group == nil || strings.TrimSpace(group.Pattern) == "" {
				return nil, errors.New("unsupported_empty_command_acl_pattern")
			}
			pattern := group.Pattern
			if group.IgnoreCase {
				pattern = "(?i)" + pattern
			}
			compiled, err := regexp.Compile(pattern)
			if err != nil {
				return nil, errors.New("unsupported_command_acl_regex_use_legacy_chen")
			}
			result = append(result, commandMatcher{compiled, rule.Action, rule.Id, group.Id})
		}
	}
	return result, nil
}

func matchPolicy(g *Grant, sql string) *commandMatcher {
	for i := range g.matchers {
		if g.matchers[i].pattern.MatchString(sql) {
			return &g.matchers[i]
		}
	}
	return nil
}

func (p *WispProvider) Authorize(ctx context.Context, g *Grant, sql string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if err := g.Check(); err != nil {
		return err
	}
	if strings.TrimSpace(sql) == "" {
		return errors.New("empty_sql")
	}
	matched := matchPolicy(g, sql)
	if matched == nil {
		return nil
	}
	e := &PolicyError{ACLID: matched.aclID, GroupID: matched.groupID}
	switch matched.action {
	case protobuf.CommandACL_Accept, protobuf.CommandACL_Warning:
		// Ordinary warnings are audited without an interactive confirmation.
		return nil
	case protobuf.CommandACL_NotifyWarning:
		e.Code, e.RiskLevel = "command_warning_confirmation_required", int32(protobuf.RiskLevel_Warning)
	case protobuf.CommandACL_Reject:
		e.Code, e.RiskLevel = "command_rejected_by_acl", int32(protobuf.RiskLevel_Reject)
	case protobuf.CommandACL_Review:
		e.Code, e.RiskLevel = "command_review_required", int32(protobuf.RiskLevel_ReviewReject)
	default:
		e.Code, e.RiskLevel = "unsupported_command_acl_action", int32(protobuf.RiskLevel_Reject)
	}
	return e
}

type maskRule struct {
	fields  []*regexp.Regexp
	method  string
	pattern string
}

func compileMaskers(rules []*protobuf.DataMaskingRule) ([]maskRule, error) {
	result := make([]maskRule, 0, len(rules))
	for _, rule := range rules {
		if rule == nil || strings.TrimSpace(rule.FieldsPattern) == "" {
			return nil, errors.New("unsupported_empty_data_masking_rule")
		}
		r := maskRule{method: rule.MaskingMethod, pattern: rule.MaskPattern}
		for _, field := range strings.Split(rule.FieldsPattern, ",") {
			field = strings.TrimSpace(field)
			if field == "" {
				continue
			}
			// Only '*' is special; a Core field pattern is not a regex.
			pattern := "(?i)^" + strings.ReplaceAll(regexp.QuoteMeta(field), `\*`, ".*") + "$"
			r.fields = append(r.fields, regexp.MustCompile(pattern))
		}
		if len(r.fields) == 0 {
			return nil, errors.New("unsupported_empty_data_masking_pattern")
		}
		result = append(result, r)
	}
	return result, nil
}

// Mask must run before any result value is exposed or persisted as a browser
// result. Matching both the source column and label preserves alias protection
// when source-column metadata is available. Last matching rule wins, as in Java.
func (p *WispProvider) Mask(g *Grant, column, label string, value any) (any, bool) {
	if g == nil {
		return "####", true
	}
	var match *maskRule
	for i := range g.maskers {
		for _, field := range g.maskers[i].fields {
			if field.MatchString(column) || field.MatchString(label) {
				match = &g.maskers[i]
				break
			}
		}
	}
	if match == nil {
		if len(g.maskers) > 0 && column == "" {
			// Without origin metadata an alias/expression can hide a protected
			// source field. Never claim label-only matching enforces that policy.
			return "####", true
		}
		return value, false
	}
	pattern := match.pattern
	if pattern == "" {
		pattern = "####"
	}
	if value == nil || match.method == "fixed_char" {
		return pattern, true
	}
	var str string
	switch v := value.(type) {
	case string:
		str = v
	case []byte:
		str = string(v)
	case bool, int, int8, int16, int32, int64, uint, uint8, uint16, uint32, uint64, float32, float64:
		str = fmt.Sprint(v)
	default:
		return pattern, true
	}
	runes := []rune(str)
	switch match.method {
	case "hide_middle":
		if len(runes) < 3 {
			return pattern, true
		}
		return string(runes[:1]) + strings.Repeat("*", len(runes)-2) + string(runes[len(runes)-1:]), true
	case "keep_prefix":
		if len(runes) <= 2 {
			return "####", true
		}
		return string(runes[:2]) + strings.Repeat("*", len(runes)-2), true
	case "keep_suffix":
		if len(runes) <= 2 {
			return "####", true
		}
		return strings.Repeat("*", len(runes)-2) + string(runes[len(runes)-2:]), true
	default:
		return pattern, true
	}
}
