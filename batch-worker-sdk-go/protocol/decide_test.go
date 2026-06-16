package protocol

import (
	"reflect"
	"testing"
)

// Edge-case unit tests ported from the TS reference (decide.test parity).

func TestClassifyHTTP_404NotFound(t *testing.T) {
	d := ClassifyHTTP(404, 0, 0, 0)
	if d.Action != "not-found" {
		t.Fatalf("404: action = %q, want not-found", d.Action)
	}
	if d.Retry == nil || *d.Retry {
		t.Fatalf("404: retry = %v, want false", d.Retry)
	}
}

func TestClassifyHTTP_FifthNonAuth4xxFailFast(t *testing.T) {
	// 4th prior non-auth 4xx already seen -> this (the 5th) hits the threshold.
	d := ClassifyHTTP(400, 4, 0, 0)
	if d.Action != "fail-fast" {
		t.Fatalf("5th 4xx: action = %q, want fail-fast", d.Action)
	}
	if d.FailFast == nil || !*d.FailFast {
		t.Fatalf("5th 4xx: failFast = %v, want true", d.FailFast)
	}
	if d.Retry == nil || *d.Retry {
		t.Fatalf("5th 4xx: retry = %v, want false", d.Retry)
	}
}

func TestClassifyHTTP_4xxBelowThresholdIsClientError(t *testing.T) {
	d := ClassifyHTTP(400, 0, 0, 0)
	if d.Action != "client-error" {
		t.Fatalf("1st 4xx: action = %q, want client-error", d.Action)
	}
}

func TestClassifyHTTP_TransportAndStatusZeroRetryThenDrop(t *testing.T) {
	for _, status := range []int{0, -1, 500, 503} {
		d := ClassifyHTTP(status, 0, 0, 0)
		if d.Action != "retry-then-drop" {
			t.Fatalf("status %d: action = %q, want retry-then-drop", status, d.Action)
		}
		if d.Retry == nil || !*d.Retry {
			t.Fatalf("status %d: retry = %v, want true", status, d.Retry)
		}
		if !reflect.DeepEqual(d.RetryBackoffMs, []int{200, 400, 800}) {
			t.Fatalf("status %d: backoff = %v, want [200 400 800]", status, d.RetryBackoffMs)
		}
		if d.MaxAttempts == nil || *d.MaxAttempts != 3 {
			t.Fatalf("status %d: maxAttempts = %v, want 3", status, d.MaxAttempts)
		}
	}
}

func TestClassifySchemaVersion(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{"v3", "reject"},    // unknown major
		{"v2-rc", "accept"}, // suffix tolerated, major v2 known
		{"", "accept"},      // null/empty -> v1
		{"v1", "accept"},
		{"v2", "accept"},
		{"banana", "reject"},
	}
	for _, c := range cases {
		if got := ClassifySchemaVersion(c.in); got != c.want {
			t.Errorf("ClassifySchemaVersion(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestExponentialBackoff(t *testing.T) {
	if got := ExponentialBackoff(200, 3); !reflect.DeepEqual(got, []int{200, 400, 800}) {
		t.Fatalf("ExponentialBackoff(200,3) = %v, want [200 400 800]", got)
	}
}

func TestParseISO8601DurationMs(t *testing.T) {
	cases := []struct {
		in   string
		want int
	}{
		{"PT15S", 15000},
		{"PT30S", 30000},
		{"PT1M30S", 90000},
		{"PT1H", 3600000},
	}
	for _, c := range cases {
		got, err := ParseISO8601DurationMs(c.in)
		if err != nil {
			t.Fatalf("ParseISO8601DurationMs(%q) error: %v", c.in, err)
		}
		if got != c.want {
			t.Errorf("ParseISO8601DurationMs(%q) = %d, want %d", c.in, got, c.want)
		}
	}
	if _, err := ParseISO8601DurationMs("garbage"); err == nil {
		t.Error("ParseISO8601DurationMs(garbage): want error, got nil")
	}
}

func TestApplyHeartbeatDirective_HintClamp(t *testing.T) {
	// raw seconds below 1s must clamp to 1000ms.
	d := ApplyHeartbeatDirective(HeartbeatResponse{NextHeartbeatHint: float64(0)})
	if d.HeartbeatNextIntervalMs == nil || *d.HeartbeatNextIntervalMs != 1000 {
		t.Fatalf("clamp: got %v, want 1000", d.HeartbeatNextIntervalMs)
	}
}
