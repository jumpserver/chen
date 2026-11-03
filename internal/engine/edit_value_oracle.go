package engine

import (
	"encoding/json"
	"errors"
	"io"
	"regexp"
	"strings"
	"time"
	"unicode/utf16"
	"unicode/utf8"

	"github.com/jumpserver/chen/internal/sqlservice"
	goora "github.com/sijms/go-ora/v2"
)

var editTimestampTZExcessPrecision = regexp.MustCompile(`[.,][0-9]{10,}`)

const editTimestampTZFormat = `'YYYY-MM-DD"T"HH24:MI:SS.FF9TZH:TZM'`

func editBaseType(t string) string {
	t = strings.ToUpper(strings.TrimSpace(t))
	if start := strings.IndexByte(t, '('); start >= 0 {
		if end := strings.IndexByte(t[start:], ')'); end >= 0 {
			t = t[:start] + t[start+end+1:]
		}
	}
	return strings.Join(strings.Fields(t), " ")
}

func editOracleTimezone(dialect string, field sqlservice.EditColumn) bool {
	return (dialect == "oracle" || dialect == "oceanbase-oracle") && editBaseType(field.DataType) == "TIMESTAMP WITH TIME ZONE"
}

func editOracleJSON(dialect string, field sqlservice.EditColumn) bool {
	return (dialect == "oracle" || dialect == "oceanbase-oracle") && editBaseType(field.DataType) == "JSON"
}

func editDriverBindValue(dialect string, field sqlservice.EditColumn, value any) any {
	if dialect == "oracle" && editOracleJSON(dialect, field) && value != nil {
		// JSON accepts a UTF-8 BLOB independently of the database/client
		// VARCHAR2 character set. A Go string bind uses that character set.
		return goora.Blob{Data: []byte(value.(string)), Valid: true}
	}
	return value
}

func editTypedLiteral(dialect string, field sqlservice.EditColumn, value any, masked bool) string {
	if dialect == "oracle" && editOracleJSON(dialect, field) && value != nil && !masked {
		value = editASCIIJSON(value.(string))
	}
	return editTypedExpression(dialect, field, editMaskSQL(dialect, value, masked))
}

// Preserve JSON spelling/number precision while making SQL preview literals
// independent of a database character set that cannot represent the document.
func editASCIIJSON(text string) string {
	const digits = "0123456789abcdef"
	var out strings.Builder
	writeUnit := func(unit rune) {
		out.WriteString(`\u`)
		for shift := 12; shift >= 0; shift -= 4 {
			out.WriteByte(digits[(unit>>shift)&15])
		}
	}
	for _, value := range text {
		if value <= 127 {
			out.WriteByte(byte(value))
		} else if value <= 0xffff {
			writeUnit(value)
		} else {
			high, low := utf16.EncodeRune(value)
			writeUnit(high)
			writeUnit(low)
		}
	}
	return out.String()
}

// Bind timezone values with an explicit, numeric-offset format. In particular,
// never reinterpret a cached value using the console's current NLS settings.
func normalizeTypedEditValue(dialect string, field sqlservice.EditColumn, value any) (any, error) {
	if value == nil {
		return nil, nil
	}
	if editOracleTimezone(dialect, field) {
		return editTimestampTZValue(value)
	}
	if editOracleJSON(dialect, field) {
		text, ok := value.(string)
		if !ok || !utf8.ValidString(text) || !json.Valid([]byte(text)) {
			return nil, errors.New("JSON must be complete UTF-8 text")
		}
		// JSON_EQUAL ignores object member order. Duplicate member names have
		// ambiguous equality semantics, so they cannot protect an optimistic edit.
		decoder := json.NewDecoder(strings.NewReader(text))
		decoder.UseNumber()
		if err := editUniqueJSONValue(decoder, 0); err != nil {
			return nil, err
		}
		if _, err := decoder.Token(); err != io.EOF {
			return nil, errors.New("JSON must contain one complete value")
		}
		return text, nil
	}
	return value, nil
}

func editTimestampTZValue(value any) (string, error) {
	var instant time.Time
	valid := false
	switch v := value.(type) {
	case time.Time:
		instant = v
		valid = true
	case string:
		if editTimestampTZExcessPrecision.MatchString(v) {
			return "", errors.New("Timestamp with time zone supports at most 9 fractional-second digits")
		}
		// Oracle's native driver materializes this type as RFC3339Nano. The
		// OceanBase text protocol also permits strings: accept only forms that
		// carry an unambiguous full year and numeric offset, including its ISO
		// session format. Two-digit years or region-only NLS output fail closed.
		for _, layout := range []string{
			time.RFC3339Nano,
			"2006-01-02 15:04:05.999999999 -07:00",
			"2006-01-02 15:04:05.999999999-07:00",
			"2006-01-02 15:04:05.999999999 -0700",
			"02-Jan-2006 03.04.05.999999999 PM -07:00",
			"02-Jan-2006 15:04:05.999999999 -07:00",
		} {
			var err error
			instant, err = time.Parse(layout, strings.TrimSpace(v))
			if err == nil {
				valid = true
				break
			}
		}
	}
	_, offset := instant.Zone()
	if !valid || instant.Year() < 1 || instant.Year() > 9999 || offset%60 != 0 || offset < -12*3600 || offset > 14*3600 {
		return "", errors.New("Timestamp with time zone requires a full year and numeric offset, for example 2026-11-02T13:14:15.123456789+08:00; ambiguous cached NLS values require an ISO session format and a refresh")
	}
	return instant.Format("2006-01-02T15:04:05.000000000-07:00"), nil
}

func editTypedExpression(dialect string, field sqlservice.EditColumn, expression string) string {
	if dialect == "oracle" && editOracleJSON(dialect, field) {
		return "JSON(" + expression + ")"
	}
	if editOracleTimezone(dialect, field) {
		return "TO_TIMESTAMP_TZ(" + expression + ", " + editTimestampTZFormat + ")"
	}
	return expression
}

func editTypedEquality(dialect string, field sqlservice.EditColumn, column, expression string) string {
	if editOracleJSON(dialect, field) {
		return "JSON_EQUAL(" + column + ", " + editTypedExpression(dialect, field, expression) + " ERROR ON ERROR)"
	}
	return column + " = " + editTypedExpression(dialect, field, expression)
}

func editUniqueJSONValue(decoder *json.Decoder, depth int) error {
	if depth > 1000 {
		return errors.New("JSON nesting exceeds the supported edit bound")
	}
	token, err := decoder.Token()
	if err != nil {
		return errors.New("JSON must contain one complete value")
	}
	delim, container := token.(json.Delim)
	if !container {
		return nil
	}
	if delim != '{' && delim != '[' {
		return errors.New("JSON must contain one complete value")
	}
	names := map[string]bool{}
	for decoder.More() {
		if delim == '{' {
			key, err := decoder.Token()
			name, ok := key.(string)
			if err != nil || !ok {
				return errors.New("JSON object keys must be strings")
			}
			if names[name] {
				return errors.New("JSON with duplicate object member names cannot protect an optimistic edit")
			}
			names[name] = true
		}
		if err := editUniqueJSONValue(decoder, depth+1); err != nil {
			return err
		}
	}
	_, err = decoder.Token()
	return err
}
