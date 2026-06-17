package protocol

import (
	"fmt"
	"math"
	"regexp"
	"slices"
	"strconv"
)

// Pure decision core for the BYO worker SDK.
//
// Each function maps a protocol input (HTTP status, heartbeat directive, renew
// response, capacity / stop signal) to a Decision whose fields match the closed
// then.expect vocabulary in the conformance fixtures. No IO here: real HTTP/Kafka
// wrappers call these and act on the result.
//
// Authoritative rules: wire-protocol.md §A (schemaVersion), §B (error codes),
// §C (retry / backoff); byo-conformance-contract.md §2 (field semantics).

// Default retry tuning (wire-protocol §C: base=200ms, maxAttempts=3).
const (
	DefaultRetryBaseMs      = 200
	DefaultRetryMaxAttempts = 3
	// ClientErrorFailFastThreshold — cumulative non-auth 4xx fail-fast threshold.
	ClientErrorFailFastThreshold = 5
	// MinHeartbeatIntervalMs — nextHeartbeatHint sanity clamp lower bound (>= 1s).
	MinHeartbeatIntervalMs = 1000
)

// Decision is the unified result carrying every then.expect field. Pointer and
// slice fields use an "isSet" convention: a nil field is unconstrained, so the
// conformance runner only asserts fields the fixture actually specifies.
type Decision struct {
	Action                  string   `json:"action,omitempty"`
	Retry                   *bool    `json:"retry,omitempty"`
	FailFast                *bool    `json:"failFast,omitempty"`
	RetryBackoffMs          []int    `json:"retryBackoffMs,omitempty"`
	MaxAttempts             *int     `json:"maxAttempts,omitempty"`
	FsmTransition           *string  `json:"fsmTransition,omitempty"`
	Kafka                   *string  `json:"kafka,omitempty"`
	StartSchedulers         []string `json:"startSchedulers,omitempty"`
	HeartbeatNextIntervalMs *int     `json:"heartbeatNextIntervalMs,omitempty"`
	EffectiveMaxConcurrent  *int     `json:"effectiveMaxConcurrent,omitempty"`
	CancelRequested         *bool    `json:"cancelRequested,omitempty"`
	Idempotent              *bool    `json:"idempotent,omitempty"`
	ReportFailure           *bool    `json:"reportFailure,omitempty"`
	Deactivate              *bool    `json:"deactivate,omitempty"`
	DrainThenDeactivate     *bool    `json:"drainThenDeactivate,omitempty"`
	ResumeWhenDrained       *bool    `json:"resumeWhenDrained,omitempty"`
	WithinMs                *int     `json:"withinMs,omitempty"`
}

// small constructors keeping the decision bodies terse.
func boolPtr(b bool) *bool    { return &b }
func intPtr(i int) *int       { return &i }
func strPtr(s string) *string { return &s }

// ExponentialBackoff computes the backoff sleep sequence: base * 2^(n-1) for
// n = 1..attempts. base=200, attempts=3 -> [200, 400, 800].
func ExponentialBackoff(baseMs, attempts int) []int {
	seq := make([]int, 0, attempts)
	for n := 1; n <= attempts; n++ {
		seq = append(seq, baseMs*(1<<(n-1)))
	}
	return seq
}

// ---------------------------------------------------------------------------
// §B / §C — HTTP status classification
// ---------------------------------------------------------------------------

// ClassifyHTTP classifies an HTTP response from a control-plane call
// (wire-protocol §B/§C).
//
//	status            HTTP status code (or 0 / negative for transport error)
//	clientErrorCount  running count of prior non-auth 4xx errors (this call
//	                  increments it; fail-fast once it reaches the threshold)
//
// baseMs / attempts tune the 5xx/transport backoff; pass 0 to use defaults.
func ClassifyHTTP(status, clientErrorCount, baseMs, attempts int) Decision {
	if baseMs <= 0 {
		baseMs = DefaultRetryBaseMs
	}
	if attempts <= 0 {
		attempts = DefaultRetryMaxAttempts
	}

	switch {
	// 2xx — success
	case status >= 200 && status < 300:
		return Decision{Action: "success", Retry: boolPtr(false)}
	// 401 / 403 — auth: fail-fast, never retry
	case status == 401 || status == 403:
		return Decision{Action: "fail-fast", FailFast: boolPtr(true), Retry: boolPtr(false)}
	// 404 — give up this request, caller decides
	case status == 404:
		return Decision{Action: "not-found", Retry: boolPtr(false), FailFast: boolPtr(false)}
	// 409 — idempotent success (already claimed / lease reclaimed)
	case status == 409:
		return Decision{
			Action:        "idempotent-success",
			Retry:         boolPtr(false),
			Idempotent:    boolPtr(true),
			ReportFailure: boolPtr(false),
		}
	// other 4xx — count toward fail-fast threshold
	case status >= 400 && status < 500:
		if clientErrorCount+1 >= ClientErrorFailFastThreshold {
			return Decision{Action: "fail-fast", FailFast: boolPtr(true), Retry: boolPtr(false)}
		}
		return Decision{Action: "client-error", Retry: boolPtr(false), FailFast: boolPtr(false)}
	// 5xx and transport errors (status <= 0 or >= 500) — exponential backoff
	default:
		return Decision{
			Action:         "retry-then-drop",
			Retry:          boolPtr(true),
			RetryBackoffMs: ExponentialBackoff(baseMs, attempts),
			MaxAttempts:    intPtr(attempts),
		}
	}
}

// ClassifyHeartbeatRenewError classifies a heartbeat / leaseRenew transport
// failure (wire-protocol §C exemption). Unlike register/claim/report (which run
// the full exponential-retry sequence), heartbeat and leaseRenew do NOT back off
// internally on a single failure — they skip this tick and retry on the next
// scheduled tick. So a 503 (or any transport-level failure) yields a single
// attempt with no retry. (A 4xx like 404 on renew is still classified by
// ClassifyHTTP → not-found/give-up.)
func ClassifyHeartbeatRenewError() Decision {
	return Decision{Action: "retry-then-drop", Retry: boolPtr(false), MaxAttempts: intPtr(1)}
}

// ---------------------------------------------------------------------------
// §A — schemaVersion classification
// ---------------------------------------------------------------------------

var schemaMajorRe = regexp.MustCompile(`^v\d+`)

// ClassifySchemaVersion decides whether a dispatch message's schemaVersion is
// processable (§A). Empty/null -> treated as v1 (accept); known major in
// SupportedSchemaVersions -> accept; unknown major (v3+) -> reject.
func ClassifySchemaVersion(version string) string {
	if version == "" {
		return "accept" // legacy orchestrator without the field -> v1
	}
	// major = leading "v<digits>" prefix; tolerate suffixes like "v2-rc".
	major := version
	if m := schemaMajorRe.FindString(version); m != "" {
		major = m
	}
	if slices.Contains(SupportedSchemaVersions, major) {
		return "accept"
	}
	return "reject"
}

// ---------------------------------------------------------------------------
// Heartbeat directive
// ---------------------------------------------------------------------------

var iso8601DurationRe = regexp.MustCompile(
	`^PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?$`)

// ParseISO8601DurationMs parses an ISO-8601 duration ("PT15S", "PT1M30S",
// "PT30S") to milliseconds.
func ParseISO8601DurationMs(iso string) (int, error) {
	m := iso8601DurationRe.FindStringSubmatch(iso)
	if m == nil || (m[1] == "" && m[2] == "" && m[3] == "") {
		return 0, fmt.Errorf("invalid ISO-8601 duration: %s", iso)
	}
	parse := func(s string) float64 {
		if s == "" {
			return 0
		}
		v, _ := strconv.ParseFloat(s, 64)
		return v
	}
	hours := parse(m[1])
	minutes := parse(m[2])
	seconds := parse(m[3])
	return int(math.Round((hours*3600 + minutes*60 + seconds) * 1000)), nil
}

// hintToMs normalizes a nextHeartbeatHint (ISO duration string or raw seconds)
// to ms, clamped to MinHeartbeatIntervalMs. The bool reports whether a usable
// hint was present.
func hintToMs(hint any) (int, bool) {
	var ms int
	switch v := hint.(type) {
	case nil:
		return 0, false
	case string:
		if v == "" {
			return 0, false
		}
		parsed, err := ParseISO8601DurationMs(v)
		if err != nil {
			return 0, false
		}
		ms = parsed
	case float64: // JSON numbers decode as float64 (seconds)
		ms = int(v * 1000)
	case int:
		ms = v * 1000
	default:
		return 0, false
	}
	if ms < MinHeartbeatIntervalMs {
		ms = MinHeartbeatIntervalMs
	}
	return ms, true
}

// ApplyHeartbeatDirective applies a heartbeat reverse-directive (wire-protocol
// §2.1). Precedence: shouldDrain/DRAINING > PAUSED > NORMAL/DEGRADED.
// nextHeartbeatHint is orthogonal and applied whenever present.
func ApplyHeartbeatDirective(resp HeartbeatResponse) Decision {
	d := Decision{Action: "apply-directive"}

	status := ""
	if resp.PlatformStatus != nil {
		status = *resp.PlatformStatus
	}
	draining := resp.ShouldDrain != nil && *resp.ShouldDrain

	switch {
	case draining || status == "DRAINING":
		d.FsmTransition = strPtr("DRAINING")
		d.Kafka = strPtr("pause")
		d.DrainThenDeactivate = boolPtr(true)
	case status == "PAUSED":
		d.FsmTransition = strPtr("PAUSED")
		d.Kafka = strPtr("pause")
	case status == "NORMAL":
		d.FsmTransition = strPtr("NORMAL")
		d.Kafka = strPtr("none")
	case status == "DEGRADED":
		d.FsmTransition = strPtr("DEGRADED")
		d.Kafka = strPtr("none")
	}

	if ms, ok := hintToMs(resp.NextHeartbeatHint); ok {
		d.HeartbeatNextIntervalMs = intPtr(ms)
	}

	// §2.1 dynamic backpressure: platform-suggested concurrency cap. Only a
	// positive value constrains; null/absent leaves local config in effect.
	if resp.DesiredMaxConcurrent != nil && *resp.DesiredMaxConcurrent > 0 {
		d.EffectiveMaxConcurrent = intPtr(*resp.DesiredMaxConcurrent)
	}

	return d
}

// ---------------------------------------------------------------------------
// Paused task types (heartbeat directive) — Kafka drop
// ---------------------------------------------------------------------------

// DecidePausedTaskType decides what to do with a freshly received Kafka dispatch
// message whose taskType is in the platform's pausedTaskTypes set (wire-protocol
// §2.1). A paused taskType is dropped WITHOUT committing the offset (platform
// redelivers after unpausing); a non-paused taskType is processed (kafka:none).
func DecidePausedTaskType(taskType string, pausedTaskTypes []string) Decision {
	if slices.Contains(pausedTaskTypes, taskType) {
		return Decision{Action: "apply-directive", Kafka: strPtr("drop-message")}
	}
	return Decision{Action: "apply-directive", Kafka: strPtr("none")}
}

// ---------------------------------------------------------------------------
// Lease renew
// ---------------------------------------------------------------------------

// ApplyRenew applies a lease-renew response (wire-protocol §2.2).
func ApplyRenew(resp RenewResponse) Decision {
	if resp.CancelRequested != nil && *resp.CancelRequested {
		return Decision{Action: "cancel", CancelRequested: boolPtr(true)}
	}
	return Decision{Action: "none"}
}

// ---------------------------------------------------------------------------
// Capacity backpressure
// ---------------------------------------------------------------------------

// DecideBackpressure decides Kafka backpressure on a freshly received message
// given current concurrency and whether the assignment is already paused.
//
// Hysteresis (aligned with Java KafkaTaskConsumer.applyBackpressure): pause when
// in-flight has reached maxConcurrent; resume only once in-flight drops BELOW
// maxConcurrent/2 (integer division, floored at 1). Pulling the pause / resume
// thresholds apart prevents in-flight from thrashing pause/resume in the
// max-1 / max band, where every resume would trigger a fresh poll burst.
//
//   - inFlight >= maxConcurrent           -> backpressure / pause (idempotent if already paused)
//   - currentlyPaused && inFlight < max/2 -> backpressure / resume
//   - otherwise                           -> none (stay paused in the [max/2, max) band)
func DecideBackpressure(inFlight, maxConcurrent int, currentlyPaused bool) Decision {
	if inFlight >= maxConcurrent {
		return Decision{
			Action:            "backpressure",
			Kafka:             strPtr("pause"),
			ResumeWhenDrained: boolPtr(true),
		}
	}
	if currentlyPaused && inFlight < resumeThreshold(maxConcurrent) {
		return Decision{
			Action: "backpressure",
			Kafka:  strPtr("resume"),
		}
	}
	return Decision{Action: "none"}
}

// resumeThreshold is the hysteresis low-water mark: in-flight must fall strictly
// below it to resume. maxConcurrent/2 (integer division), floored at 1 so a
// maxConcurrent of 1 still has a reachable resume point.
func resumeThreshold(maxConcurrent int) int {
	t := maxConcurrent / 2
	if t < 1 {
		return 1
	}
	return t
}

// ---------------------------------------------------------------------------
// Graceful stop
// ---------------------------------------------------------------------------

// PlanStop builds the graceful-stop plan (wire-protocol §4 stop sequence).
func PlanStop(timeoutMs int) Decision {
	return Decision{
		Action:              "graceful-stop",
		Kafka:               strPtr("wakeup"),
		Deactivate:          boolPtr(true),
		DrainThenDeactivate: boolPtr(true),
		WithinMs:            intPtr(timeoutMs),
	}
}

// ---------------------------------------------------------------------------
// Register
// ---------------------------------------------------------------------------

// DecideRegister decides the register success path (wire-protocol §4). 200 is
// success whether the platform created a fresh record or idempotently reused an
// existing one; pass idempotent=true when the (tenant, workerCode) already
// existed.
func DecideRegister(idempotent bool) Decision {
	d := Decision{
		Action:          "register-online",
		FsmTransition:   strPtr("NORMAL"),
		StartSchedulers: []string{"heartbeat", "leaseRenew"},
		Kafka:           strPtr("subscribe"),
	}
	if idempotent {
		d.Idempotent = boolPtr(true)
	}
	return d
}
