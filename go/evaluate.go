package flagbearer

import (
	"bytes"
	"crypto/sha256"
	"encoding/json"
	"io"
	"math"
	"strings"
)

type reason string

const (
	reasonOff      reason = "off"
	reasonTester   reason = "tester"
	reasonRollout  reason = "rollout"
	reasonFallback reason = "fallback"
)

const maxSafeInteger = 1<<53 - 1

type flagRule struct {
	key            string
	enabled        bool
	rolloutBps     int64
	overrideEmails []string
}

type ruleset struct {
	environmentID string
	version       int64
	flags         map[string]flagRule
}

type evaluation struct {
	value   bool
	reason  reason
	version *int64
}

// bucket is a user's place in a flag's rollout, 0–9999. Below rolloutBps means on.
func bucket(flagKey, userKey string) int64 {
	digest := sha256.Sum256([]byte(flagKey + ":" + userKey))
	// The digest read as a big-endian integer, mod 10000, one byte at a time.
	var remainder int64
	for _, b := range digest {
		remainder = (remainder*256 + int64(b)) % 10000
	}
	return remainder
}

func evaluate(flag *flagRule, user User, version *int64, fallback bool) evaluation {
	if flag == nil {
		return evaluation{fallback, reasonFallback, version}
	}
	if !flag.enabled {
		return evaluation{false, reasonOff, version}
	}
	if email := strings.ToLower(strings.TrimSpace(user.Email)); email != "" {
		for _, item := range flag.overrideEmails {
			if strings.ToLower(strings.TrimSpace(item)) == email {
				return evaluation{true, reasonTester, version}
			}
		}
	}
	if user.Key == "" {
		if flag.rolloutBps != 0 && flag.rolloutBps != 10000 {
			return evaluation{fallback, reasonFallback, version}
		}
		return evaluation{flag.rolloutBps == 10000, reasonRollout, version}
	}
	return evaluation{bucket(flag.key, user.Key) < flag.rolloutBps, reasonRollout, version}
}

// integer accepts a JSON number with no fractional part, as JavaScript does.
func integer(value any) (int64, bool) {
	number, ok := value.(json.Number)
	if !ok {
		return 0, false
	}
	if n, err := number.Int64(); err == nil {
		return n, true
	}
	f, err := number.Float64()
	if err != nil || f != math.Trunc(f) || math.Abs(f) > maxSafeInteger {
		return 0, false
	}
	return int64(f), true
}

func parseVersion(value any) (int64, bool) {
	n, ok := integer(value)
	return n, ok && n >= 0 && n <= maxSafeInteger
}

// parseRuleset reads a GET /v1/ruleset body, rejecting it whole if any part is invalid.
func parseRuleset(body []byte) (*ruleset, bool) {
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.UseNumber()
	var value any
	if decoder.Decode(&value) != nil {
		return nil, false
	}
	// Like JSON.parse, nothing but whitespace may follow the value.
	if _, err := decoder.Token(); err != io.EOF {
		return nil, false
	}
	object, ok := value.(map[string]any)
	if !ok {
		return nil, false
	}
	environmentID, ok := object["environmentId"].(string)
	if !ok {
		return nil, false
	}
	version, ok := parseVersion(object["version"])
	if !ok {
		return nil, false
	}
	items, ok := object["flags"].([]any)
	if !ok {
		return nil, false
	}
	flags := make(map[string]flagRule, len(items))
	for _, item := range items {
		rule, ok := parseFlagRule(item)
		if !ok {
			return nil, false
		}
		flags[rule.key] = rule
	}
	return &ruleset{environmentID, version, flags}, true
}

func parseFlagRule(value any) (flagRule, bool) {
	object, ok := value.(map[string]any)
	if !ok {
		return flagRule{}, false
	}
	key, keyOK := object["key"].(string)
	enabled, enabledOK := object["enabled"].(bool)
	rollout, rolloutOK := integer(object["rolloutBps"])
	emails, emailsOK := object["overrideEmails"].([]any)
	if !keyOK || !enabledOK || !rolloutOK || rollout < 0 || rollout > 10000 || !emailsOK {
		return flagRule{}, false
	}
	overrides := make([]string, len(emails))
	for index, email := range emails {
		text, ok := email.(string)
		if !ok {
			return flagRule{}, false
		}
		overrides[index] = text
	}
	return flagRule{key, enabled, rollout, overrides}, true
}
