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

// DecideBackpressure max/2 hysteresis (#8), aligned with Java
// KafkaTaskConsumer.applyBackpressure: pause >= max; resume only when in-flight
// drops below max/2; stay paused in the [max/2, max) band so the max-1 / max
// boundary does not thrash pause/resume.
func TestDecideBackpressure_Hysteresis(t *testing.T) {
	kafkaOf := func(d Decision) string {
		if d.Kafka == nil {
			return ""
		}
		return *d.Kafka
	}
	cases := []struct {
		name       string
		inFlight   int
		max        int
		paused     bool
		wantAction string
		wantKafka  string
	}{
		// pause edge: at or over capacity always pauses (fixture 11 contract).
		{"at-capacity pauses", 4, 4, false, "backpressure", "pause"},
		{"over-capacity pauses", 5, 4, false, "backpressure", "pause"},
		// already paused, at capacity -> still pause (idempotent), never resume.
		{"paused at capacity stays pause", 4, 4, true, "backpressure", "pause"},
		// max-1 just below cap while paused: must NOT resume (in hysteresis band).
		{"paused max-1 no resume", 3, 4, true, "none", ""},
		// in the [max/2, max) band while paused: hold paused, no flapping.
		{"paused at max/2 holds (=2, not < 2)", 2, 4, true, "none", ""},
		// drop below max/2 while paused -> resume.
		{"paused below max/2 resumes", 1, 4, true, "backpressure", "resume"},
		// not paused and below cap -> nothing to do.
		{"not paused below cap none", 1, 4, false, "none", ""},
		// max=10 ladder mirrors the Java hysteresis test: 6 holds, 5 holds, 4 resumes.
		{"max10 inflight6 holds", 6, 10, true, "none", ""},
		{"max10 inflight5 holds (=5 not < 5)", 5, 10, true, "none", ""},
		{"max10 inflight4 resumes", 4, 10, true, "backpressure", "resume"},
		// max=1 floors resume threshold at 1: inflight 0 resumes.
		{"max1 inflight0 resumes", 0, 1, true, "backpressure", "resume"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			d := DecideBackpressure(c.inFlight, c.max, c.paused)
			if d.Action != c.wantAction {
				t.Fatalf("action = %q, want %q", d.Action, c.wantAction)
			}
			if got := kafkaOf(d); got != c.wantKafka {
				t.Fatalf("kafka = %q, want %q", got, c.wantKafka)
			}
		})
	}
}
