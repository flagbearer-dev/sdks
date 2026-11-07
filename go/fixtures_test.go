package flagbearer

import (
	"encoding/json"
	"os"
	"testing"
)

type fixtureFile struct {
	Buckets []struct {
		Flag   string `json:"flag"`
		User   string `json:"user"`
		Bucket int64  `json:"bucket"`
	} `json:"buckets"`
	Cases []struct {
		Name    string          `json:"name"`
		Flag    json.RawMessage `json:"flag"`
		User    User            `json:"user"`
		Default bool            `json:"default"`
		Expect  struct {
			Value  bool   `json:"value"`
			Reason reason `json:"reason"`
		} `json:"expect"`
	} `json:"cases"`
}

func loadFixtures(t *testing.T) fixtureFile {
	t.Helper()
	data, err := os.ReadFile("../fixtures/evaluation.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixtures fixtureFile
	if err := json.Unmarshal(data, &fixtures); err != nil {
		t.Fatal(err)
	}
	return fixtures
}

func TestFixtureBuckets(t *testing.T) {
	for _, item := range loadFixtures(t).Buckets {
		if got := bucket(item.Flag, item.User); got != item.Bucket {
			t.Errorf("bucket(%q, %.20q) = %d, want %d", item.Flag, item.User, got, item.Bucket)
		}
	}
}

func TestFixtureCases(t *testing.T) {
	for _, item := range loadFixtures(t).Cases {
		t.Run(item.Name, func(t *testing.T) {
			var rule *flagRule
			if string(item.Flag) != "null" {
				body := `{"environmentId":"1","version":7,"flags":[` + string(item.Flag) + `]}`
				parsed, ok := parseRuleset([]byte(body))
				if !ok {
					t.Fatal("fixture flag didn't parse")
				}
				for _, found := range parsed.flags {
					rule = &found
				}
			}
			version := int64(7)
			got := evaluate(rule, item.User, &version, item.Default)
			if got.value != item.Expect.Value || got.reason != item.Expect.Reason || *got.version != 7 {
				t.Errorf("got %v/%s, want %v/%s", got.value, got.reason, item.Expect.Value, item.Expect.Reason)
			}
		})
	}
}

func TestInvalidRulesRejectTheWholeRuleset(t *testing.T) {
	for _, flag := range []string{
		`{"key":"a","enabled":true,"rolloutBps":10001,"overrideEmails":[]}`,
		`{"key":"a","enabled":true,"rolloutBps":50.5,"overrideEmails":[]}`,
		`{"key":"a","enabled":true,"rolloutBps":true,"overrideEmails":[]}`,
		`{"key":"a","enabled":1,"rolloutBps":0,"overrideEmails":[]}`,
		`{"key":"a","enabled":true,"rolloutBps":0,"overrideEmails":[1]}`,
		`{"key":"a","enabled":true,"rolloutBps":0}`,
	} {
		body := `{"environmentId":"1","version":1,"flags":[{"key":"b","enabled":true,"rolloutBps":0,"overrideEmails":[]},` + flag + `]}`
		if _, ok := parseRuleset([]byte(body)); ok {
			t.Errorf("accepted %s", flag)
		}
	}
	if _, ok := parseRuleset([]byte(`{"environmentId":"1","version":1,"flags":[]} trailing`)); ok {
		t.Error("accepted trailing data")
	}
	if _, ok := parseRuleset([]byte(`{"environmentId":"1","version":1.0,"flags":[]}`)); !ok {
		t.Error("rejected an integral float version")
	}
}
