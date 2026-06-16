//! Pure decision core for the BYO worker SDK.
//!
//! Each function maps a protocol input (HTTP status, heartbeat directive, renew
//! response, capacity / stop signal) to a [`Decision`] whose fields match the
//! closed `then.expect` vocabulary in the conformance fixtures. No IO here: real
//! HTTP/Kafka wrappers call these and act on the result.
//!
//! Authoritative rules: wire-protocol.md §A (schemaVersion), §B (error codes),
//! §C (retry / backoff); byo-conformance-contract.md §2 (field semantics).

use crate::constants::SUPPORTED_SCHEMA_VERSIONS;
use crate::protocol::{HeartbeatHint, HeartbeatResponse, RenewResponse};

/// Default retry tuning (wire-protocol §C: base=200ms, maxAttempts=3).
pub const DEFAULT_RETRY_BASE_MS: i64 = 200;
pub const DEFAULT_RETRY_MAX_ATTEMPTS: i64 = 3;
/// Cumulative non-auth 4xx fail-fast threshold.
pub const CLIENT_ERROR_FAIL_FAST_THRESHOLD: i64 = 5;
/// `nextHeartbeatHint` sanity clamp lower bound (must not drift below 1s).
pub const MIN_HEARTBEAT_INTERVAL_MS: i64 = 1000;

/// Schema-version classification outcome (§A).
pub const ACCEPT: &str = "accept";
pub const REJECT: &str = "reject";

/// The unified decision result carrying every `then.expect` field.
///
/// Every field is `Option`/`Vec`: a `None` (or empty `Vec`) field is
/// unconstrained, so the conformance runner only asserts fields the fixture
/// actually specifies. `action` is always present.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Decision {
    pub action: String,
    pub retry: Option<bool>,
    pub fail_fast: Option<bool>,
    pub retry_backoff_ms: Option<Vec<i64>>,
    pub max_attempts: Option<i64>,
    pub fsm_transition: Option<String>,
    pub kafka: Option<String>,
    pub start_schedulers: Option<Vec<String>>,
    pub heartbeat_next_interval_ms: Option<i64>,
    pub cancel_requested: Option<bool>,
    pub idempotent: Option<bool>,
    pub report_failure: Option<bool>,
    pub deactivate: Option<bool>,
    pub drain_then_deactivate: Option<bool>,
    pub resume_when_drained: Option<bool>,
    pub within_ms: Option<i64>,
}

impl Decision {
    fn with_action(action: &str) -> Self {
        Decision {
            action: action.to_string(),
            ..Default::default()
        }
    }
}

/// Compute the exponential backoff sleep sequence: `base * 2^(n-1)` for
/// `n = 1..=attempts`. base=200, attempts=3 -> `[200, 400, 800]`.
pub fn exponential_backoff(base_ms: i64, attempts: i64) -> Vec<i64> {
    let mut seq = Vec::with_capacity(attempts.max(0) as usize);
    let mut n = 1;
    while n <= attempts {
        seq.push(base_ms * (1 << (n - 1)));
        n += 1;
    }
    seq
}

// ---------------------------------------------------------------------------
// §B / §C — HTTP status classification
// ---------------------------------------------------------------------------

/// Classify an HTTP response from a control-plane call (wire-protocol §B/§C).
///
/// * `status` — HTTP status code (or 0 / negative for transport error)
/// * `client_error_count` — running count of prior non-auth 4xx errors (this
///   call increments it; fail-fast once it reaches the threshold)
/// * `base_ms` / `attempts` — tune the 5xx/transport backoff; pass `<= 0` to use
///   defaults.
pub fn classify_http(status: i64, client_error_count: i64, base_ms: i64, attempts: i64) -> Decision {
    let base_ms = if base_ms <= 0 {
        DEFAULT_RETRY_BASE_MS
    } else {
        base_ms
    };
    let attempts = if attempts <= 0 {
        DEFAULT_RETRY_MAX_ATTEMPTS
    } else {
        attempts
    };

    if (200..300).contains(&status) {
        // 2xx — success
        let mut d = Decision::with_action("success");
        d.retry = Some(false);
        d
    } else if status == 401 || status == 403 {
        // 401 / 403 — auth: fail-fast, never retry
        let mut d = Decision::with_action("fail-fast");
        d.fail_fast = Some(true);
        d.retry = Some(false);
        d
    } else if status == 404 {
        // 404 — give up this request, caller decides
        let mut d = Decision::with_action("not-found");
        d.retry = Some(false);
        d
    } else if status == 409 {
        // 409 — idempotent success (already claimed / lease reclaimed)
        let mut d = Decision::with_action("idempotent-success");
        d.retry = Some(false);
        d.idempotent = Some(true);
        d.report_failure = Some(false);
        d
    } else if (400..500).contains(&status) {
        // other 4xx — count toward fail-fast threshold
        if client_error_count + 1 >= CLIENT_ERROR_FAIL_FAST_THRESHOLD {
            let mut d = Decision::with_action("fail-fast");
            d.fail_fast = Some(true);
            d.retry = Some(false);
            d
        } else {
            let mut d = Decision::with_action("client-error");
            d.retry = Some(false);
            d
        }
    } else {
        // 5xx and transport errors (status <= 0 or >= 500) — exponential backoff
        let mut d = Decision::with_action("retry-then-drop");
        d.retry = Some(true);
        d.retry_backoff_ms = Some(exponential_backoff(base_ms, attempts));
        d.max_attempts = Some(attempts);
        d
    }
}

// ---------------------------------------------------------------------------
// §A — schemaVersion classification
// ---------------------------------------------------------------------------

/// Extract the leading `v<digits>` major prefix (equivalent to `^v\d+`), or
/// `None` if the string does not start with `v` followed by at least one digit.
fn schema_major(version: &str) -> Option<&str> {
    let bytes = version.as_bytes();
    if bytes.first() != Some(&b'v') {
        return None;
    }
    let mut end = 1;
    while end < bytes.len() && bytes[end].is_ascii_digit() {
        end += 1;
    }
    if end == 1 {
        // "v" with no following digit
        return None;
    }
    Some(&version[..end])
}

/// Decide whether a dispatch message's `schemaVersion` is processable (§A).
/// null/empty -> treated as v1 (accept); known major in
/// [`SUPPORTED_SCHEMA_VERSIONS`] -> accept; unknown major (v3+) -> reject.
pub fn classify_schema_version(version: Option<&str>) -> &'static str {
    let version = match version {
        None => return ACCEPT, // legacy orchestrator without the field -> v1
        Some("") => return ACCEPT,
        Some(v) => v,
    };
    // major = leading "v<digits>" prefix; tolerate suffixes like "v2-rc".
    let major = schema_major(version).unwrap_or(version);
    if SUPPORTED_SCHEMA_VERSIONS.contains(&major) {
        ACCEPT
    } else {
        REJECT
    }
}

// ---------------------------------------------------------------------------
// Heartbeat directive
// ---------------------------------------------------------------------------

/// Parse an ISO-8601 duration ("PT15S", "PT1M30S", "PT30S", "PT1H") to
/// milliseconds. Equivalent to
/// `^PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?$`.
///
/// Returns `Err` for malformed input or when no H/M/S component is present.
pub fn parse_iso8601_duration_ms(iso: &str) -> Result<i64, String> {
    let rest = iso
        .strip_prefix("PT")
        .ok_or_else(|| format!("invalid ISO-8601 duration: {iso}"))?;

    let mut hours: Option<f64> = None;
    let mut minutes: Option<f64> = None;
    let mut seconds: Option<f64> = None;

    let bytes = rest.as_bytes();
    let mut i = 0;
    // Components must appear in H, M, S order; each is `<number><unit>`.
    // Track the last unit seen to enforce ordering and reject duplicates.
    let mut last_unit_rank = 0u8; // H=1, M=2, S=3
    while i < bytes.len() {
        // parse a number: digits with an optional single '.' fractional part
        let start = i;
        let mut seen_dot = false;
        while i < bytes.len() {
            let c = bytes[i];
            if c.is_ascii_digit() {
                i += 1;
            } else if c == b'.' && !seen_dot {
                seen_dot = true;
                i += 1;
            } else {
                break;
            }
        }
        if i == start {
            return Err(format!("invalid ISO-8601 duration: {iso}"));
        }
        let num_str = &rest[start..i];
        // a bare '.' or trailing-only dot is not a valid float component
        let value: f64 = num_str
            .parse()
            .map_err(|_| format!("invalid ISO-8601 duration: {iso}"))?;

        if i >= bytes.len() {
            return Err(format!("invalid ISO-8601 duration: {iso}"));
        }
        let unit = bytes[i];
        i += 1;
        let rank = match unit {
            b'H' => 1,
            b'M' => 2,
            b'S' => 3,
            _ => return Err(format!("invalid ISO-8601 duration: {iso}")),
        };
        if rank <= last_unit_rank {
            return Err(format!("invalid ISO-8601 duration: {iso}"));
        }
        last_unit_rank = rank;
        match unit {
            b'H' => hours = Some(value),
            b'M' => minutes = Some(value),
            b'S' => seconds = Some(value),
            _ => unreachable!(),
        }
    }

    if hours.is_none() && minutes.is_none() && seconds.is_none() {
        return Err(format!("invalid ISO-8601 duration: {iso}"));
    }

    let h = hours.unwrap_or(0.0);
    let m = minutes.unwrap_or(0.0);
    let s = seconds.unwrap_or(0.0);
    Ok(((h * 3600.0 + m * 60.0 + s) * 1000.0).round() as i64)
}

/// Normalize a `nextHeartbeatHint` (ISO duration string or raw seconds) to ms,
/// clamped to [`MIN_HEARTBEAT_INTERVAL_MS`]. Returns `None` when no usable hint
/// is present.
fn hint_to_ms(hint: &HeartbeatHint) -> Option<i64> {
    let ms = match hint {
        HeartbeatHint::Iso(s) => {
            if s.is_empty() {
                return None;
            }
            match parse_iso8601_duration_ms(s) {
                Ok(v) => v,
                Err(_) => return None,
            }
        }
        HeartbeatHint::Seconds(secs) => (secs * 1000.0) as i64,
    };
    Some(ms.max(MIN_HEARTBEAT_INTERVAL_MS))
}

/// Apply a heartbeat reverse-directive (wire-protocol §2.1). Precedence:
/// shouldDrain/DRAINING > PAUSED > NORMAL/DEGRADED. `nextHeartbeatHint` is
/// orthogonal and applied whenever present.
pub fn apply_heartbeat_directive(resp: &HeartbeatResponse) -> Decision {
    let mut d = Decision::with_action("apply-directive");

    let status = resp.platform_status.as_deref().unwrap_or("");
    let draining = resp.should_drain == Some(true);

    if draining || status == "DRAINING" {
        d.fsm_transition = Some("DRAINING".to_string());
        d.kafka = Some("pause".to_string());
        d.drain_then_deactivate = Some(true);
    } else if status == "PAUSED" {
        d.fsm_transition = Some("PAUSED".to_string());
        d.kafka = Some("pause".to_string());
    } else if status == "NORMAL" {
        d.fsm_transition = Some("NORMAL".to_string());
        d.kafka = Some("none".to_string());
    } else if status == "DEGRADED" {
        d.fsm_transition = Some("DEGRADED".to_string());
        d.kafka = Some("none".to_string());
    }

    if let Some(hint) = &resp.next_heartbeat_hint {
        if let Some(ms) = hint_to_ms(hint) {
            d.heartbeat_next_interval_ms = Some(ms);
        }
    }

    d
}

// ---------------------------------------------------------------------------
// Lease renew
// ---------------------------------------------------------------------------

/// Apply a lease-renew response (wire-protocol §2.2).
pub fn apply_renew(resp: &RenewResponse) -> Decision {
    if resp.cancel_requested == Some(true) {
        let mut d = Decision::with_action("cancel");
        d.cancel_requested = Some(true);
        d
    } else {
        Decision::with_action("none")
    }
}

// ---------------------------------------------------------------------------
// Capacity backpressure
// ---------------------------------------------------------------------------

/// Decide Kafka backpressure on a freshly received message given current
/// concurrency. When in-flight has reached `max_concurrent`, pause the
/// assignment and resume once one slot drains.
pub fn decide_backpressure(in_flight: i64, max_concurrent: i64) -> Decision {
    if in_flight >= max_concurrent {
        let mut d = Decision::with_action("backpressure");
        d.kafka = Some("pause".to_string());
        d.resume_when_drained = Some(true);
        d
    } else {
        Decision::with_action("none")
    }
}

// ---------------------------------------------------------------------------
// Graceful stop
// ---------------------------------------------------------------------------

/// Build the graceful-stop plan (wire-protocol §4 stop sequence).
pub fn plan_stop(timeout_ms: i64) -> Decision {
    let mut d = Decision::with_action("graceful-stop");
    d.kafka = Some("wakeup".to_string());
    d.deactivate = Some(true);
    d.drain_then_deactivate = Some(true);
    d.within_ms = Some(timeout_ms);
    d
}

// ---------------------------------------------------------------------------
// Register
// ---------------------------------------------------------------------------

/// Decide the register success path (wire-protocol §4). 200 is success whether
/// the platform created a fresh record or idempotently reused an existing one;
/// pass `idempotent = true` when the (tenant, workerCode) already existed.
pub fn decide_register(idempotent: bool) -> Decision {
    let mut d = Decision::with_action("register-online");
    d.fsm_transition = Some("NORMAL".to_string());
    d.start_schedulers = Some(vec!["heartbeat".to_string(), "leaseRenew".to_string()]);
    d.kafka = Some("subscribe".to_string());
    if idempotent {
        d.idempotent = Some(true);
    }
    d
}

// ---------------------------------------------------------------------------
// Unit tests — edge cases ported from the TS/Go reference (decide parity).
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::HeartbeatHint;

    #[test]
    fn classify_http_404_not_found() {
        let d = classify_http(404, 0, 0, 0);
        assert_eq!(d.action, "not-found");
        assert_eq!(d.retry, Some(false));
    }

    #[test]
    fn classify_http_fifth_non_auth_4xx_fail_fast() {
        // 4 prior non-auth 4xx already seen -> this (the 5th) hits the threshold.
        let d = classify_http(400, 4, 0, 0);
        assert_eq!(d.action, "fail-fast");
        assert_eq!(d.fail_fast, Some(true));
        assert_eq!(d.retry, Some(false));
    }

    #[test]
    fn classify_http_4xx_below_threshold_is_client_error() {
        let d = classify_http(400, 0, 0, 0);
        assert_eq!(d.action, "client-error");
    }

    #[test]
    fn classify_http_auth_fail_fast() {
        for status in [401, 403] {
            let d = classify_http(status, 0, 0, 0);
            assert_eq!(d.action, "fail-fast");
            assert_eq!(d.fail_fast, Some(true));
            assert_eq!(d.retry, Some(false));
        }
    }

    #[test]
    fn classify_http_409_idempotent_success() {
        let d = classify_http(409, 0, 0, 0);
        assert_eq!(d.action, "idempotent-success");
        assert_eq!(d.idempotent, Some(true));
        assert_eq!(d.report_failure, Some(false));
        assert_eq!(d.retry, Some(false));
    }

    #[test]
    fn classify_http_transport_and_status_zero_retry_then_drop() {
        for status in [0, -1, 500, 503] {
            let d = classify_http(status, 0, 0, 0);
            assert_eq!(d.action, "retry-then-drop", "status {status}");
            assert_eq!(d.retry, Some(true), "status {status}");
            assert_eq!(d.retry_backoff_ms, Some(vec![200, 400, 800]), "status {status}");
            assert_eq!(d.max_attempts, Some(3), "status {status}");
        }
    }

    #[test]
    fn classify_schema_version_cases() {
        let cases = [
            ("v3", "reject"),    // unknown major
            ("v2-rc", "accept"), // suffix tolerated, major v2 known
            ("", "accept"),      // empty -> v1
            ("v1", "accept"),
            ("v2", "accept"),
            ("banana", "reject"),
        ];
        for (input, want) in cases {
            assert_eq!(classify_schema_version(Some(input)), want, "input {input:?}");
        }
        // null/absent -> accept
        assert_eq!(classify_schema_version(None), "accept");
    }

    #[test]
    fn exponential_backoff_default() {
        assert_eq!(exponential_backoff(200, 3), vec![200, 400, 800]);
    }

    #[test]
    fn parse_iso8601_duration_cases() {
        let cases = [
            ("PT15S", 15000),
            ("PT30S", 30000),
            ("PT1M30S", 90000),
            ("PT1H", 3600000),
        ];
        for (input, want) in cases {
            assert_eq!(parse_iso8601_duration_ms(input).unwrap(), want, "input {input}");
        }
        assert!(parse_iso8601_duration_ms("garbage").is_err());
    }

    #[test]
    fn apply_heartbeat_directive_hint_clamp() {
        // raw seconds below 1s must clamp to 1000ms.
        let resp = HeartbeatResponse {
            next_heartbeat_hint: Some(HeartbeatHint::Seconds(0.0)),
            ..Default::default()
        };
        let d = apply_heartbeat_directive(&resp);
        assert_eq!(d.heartbeat_next_interval_ms, Some(1000));
    }

    #[test]
    fn apply_heartbeat_directive_draining_precedence() {
        let resp = HeartbeatResponse {
            platform_status: Some("PAUSED".to_string()),
            should_drain: Some(true),
            ..Default::default()
        };
        let d = apply_heartbeat_directive(&resp);
        assert_eq!(d.fsm_transition.as_deref(), Some("DRAINING"));
        assert_eq!(d.kafka.as_deref(), Some("pause"));
        assert_eq!(d.drain_then_deactivate, Some(true));
    }

    #[test]
    fn apply_renew_cancel_and_none() {
        let cancel = apply_renew(&RenewResponse {
            cancel_requested: Some(true),
            ..Default::default()
        });
        assert_eq!(cancel.action, "cancel");
        assert_eq!(cancel.cancel_requested, Some(true));

        let none = apply_renew(&RenewResponse::default());
        assert_eq!(none.action, "none");
        assert_eq!(none.cancel_requested, None);
    }

    #[test]
    fn decide_backpressure_pause_and_none() {
        let paused = decide_backpressure(4, 4);
        assert_eq!(paused.action, "backpressure");
        assert_eq!(paused.kafka.as_deref(), Some("pause"));
        assert_eq!(paused.resume_when_drained, Some(true));

        let none = decide_backpressure(2, 4);
        assert_eq!(none.action, "none");
    }

    #[test]
    fn plan_stop_fields() {
        let d = plan_stop(30000);
        assert_eq!(d.action, "graceful-stop");
        assert_eq!(d.kafka.as_deref(), Some("wakeup"));
        assert_eq!(d.deactivate, Some(true));
        assert_eq!(d.drain_then_deactivate, Some(true));
        assert_eq!(d.within_ms, Some(30000));
    }

    #[test]
    fn decide_register_idempotent_flag() {
        let fresh = decide_register(false);
        assert_eq!(fresh.action, "register-online");
        assert_eq!(fresh.fsm_transition.as_deref(), Some("NORMAL"));
        assert_eq!(
            fresh.start_schedulers,
            Some(vec!["heartbeat".to_string(), "leaseRenew".to_string()])
        );
        assert_eq!(fresh.kafka.as_deref(), Some("subscribe"));
        assert_eq!(fresh.idempotent, None);

        let reused = decide_register(true);
        assert_eq!(reused.idempotent, Some(true));
    }
}
