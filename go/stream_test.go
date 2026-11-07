package flagbearer

import (
	"fmt"
	"slices"
	"strings"
	"testing"
)

func parse(t *testing.T, pieces ...string) ([]int64, error) {
	t.Helper()
	var versions []int64
	parser := newEventParser(func(version int64) { versions = append(versions, version) })
	for _, piece := range pieces {
		if err := parser.feed([]byte(piece)); err != nil {
			return versions, err
		}
	}
	return versions, nil
}

func TestReadsVersionEventsAndIgnoresOthers(t *testing.T) {
	got, _ := parse(t,
		"retry: 9000\n\n",
		": comment\n",
		"event: version\ndata: {\"version\":4}\n\n",
		"event: ping\ndata: {}\n\n",
		"data: {\"version\":99}\n\n",
	)
	if !slices.Equal(got, []int64{4}) {
		t.Fatalf("got %v", got)
	}
}

func TestAnyLineEndingSplitAnywhere(t *testing.T) {
	for _, ending := range []string{"\n", "\r\n", "\r"} {
		text := fmt.Sprintf("event: version%sdata: {\"version\":7}%s%s", ending, ending, ending)
		for cut := 0; cut <= len(text); cut++ {
			// A trailing CR might be half a CRLF, so it's read once the next byte arrives.
			got, _ := parse(t, text[:cut], text[cut:], ": heartbeat\n")
			if !slices.Equal(got, []int64{7}) {
				t.Fatalf("ending %q cut %d: got %v", ending, cut, got)
			}
		}
	}
}

func TestMultiByteCharactersSplitAcrossReads(t *testing.T) {
	text := "event: version\ndata: {\"version\":5,\"note\":\"ü\"}\n\n"
	for cut := 0; cut <= len(text); cut++ {
		got, _ := parse(t, text[:cut], text[cut:])
		if !slices.Equal(got, []int64{5}) {
			t.Fatalf("cut %d: got %v", cut, got)
		}
	}
}

func TestMultiLineDataJoinsWithNewlines(t *testing.T) {
	got, _ := parse(t, "event: version\ndata: {\"version\":\ndata: 5}\n\n")
	if !slices.Equal(got, []int64{5}) {
		t.Fatalf("got %v", got)
	}
}

func TestMalformedVersionsAreIgnored(t *testing.T) {
	for _, data := range []string{"nope", `{"version":-1}`, `{"version":true}`, `{"version":1.5}`, "[]"} {
		got, err := parse(t, "event: version\ndata: "+data+"\n\n")
		if len(got) != 0 || err != nil {
			t.Errorf("%s: got %v, %v", data, got, err)
		}
	}
}

func TestOversizedEventsFailTheConnection(t *testing.T) {
	if _, err := parse(t, "data: "+strings.Repeat("x", maxEventLength)+"\n"); err == nil {
		t.Error("oversized data accepted")
	}
	if _, err := parse(t, strings.Repeat("x", maxEventLength+1)); err == nil {
		t.Error("oversized line accepted")
	}
}
