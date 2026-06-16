//! Control-plane transport — the 6 stable `/internal/*` calls (§1.1) behind a
//! trait, plus an in-memory [`FakeTransport`] for tests and a documented
//! [`HttpTransport`] stub.
//!
//! Mirrors the Java SDK `PlatformHttpClient` and the TS/Go phase-2 transport.
//! **Response handling routes every status through [`classify_http`]** and turns
//! the resulting [`Decision`] into a [`TransportOutcome`] the caller (scheduler /
//! lifecycle) acts on: retry-with-backoff, fatal fail-fast, or idempotent
//! success.
//!
//! Zero external deps: std has no HTTP client, so the *real* client is a future
//! adapter — see [`HttpTransport`]. The engine is exercised end-to-end against
//! [`FakeTransport`].

use std::collections::VecDeque;
use std::sync::{Arc, Mutex};

use crate::decide::{classify_http, Decision};

/// A raw HTTP response from a control-plane call: the status code (or `<= 0`
/// for a transport error) plus an opaque body the caller parses.
#[derive(Debug, Clone, Default)]
pub struct HttpResponse {
    pub status: i64,
    pub body: String,
}

impl HttpResponse {
    pub fn new(status: i64, body: &str) -> Self {
        Self {
            status,
            body: body.to_string(),
        }
    }
    /// A transport-level error (DNS/connect/timeout) — modeled as status 0.
    pub fn transport_error() -> Self {
        Self {
            status: 0,
            body: String::new(),
        }
    }
}

/// What the caller should do after a control-plane call, derived from
/// [`classify_http`]. This is the engine-facing projection of [`Decision`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TransportOutcome {
    /// 2xx — proceed.
    Success,
    /// 409 — treat as success (already claimed / lease reclaimed).
    IdempotentSuccess,
    /// 5xx / transport error — retry using the backoff schedule (ms per attempt).
    Retry { backoff_ms: Vec<i64>, max_attempts: i64 },
    /// 401/403 or cumulative-4xx threshold — stop, do not retry.
    FailFast,
    /// 404 — give up this request without failing the worker.
    NotFound,
    /// Other non-fatal 4xx below the fail-fast threshold.
    ClientError,
}

/// Map a [`Decision`] (from `classify_http`) to a [`TransportOutcome`].
pub fn outcome_from_decision(d: &Decision) -> TransportOutcome {
    match d.action.as_str() {
        "success" => TransportOutcome::Success,
        "idempotent-success" => TransportOutcome::IdempotentSuccess,
        "fail-fast" => TransportOutcome::FailFast,
        "not-found" => TransportOutcome::NotFound,
        "client-error" => TransportOutcome::ClientError,
        // "retry-then-drop"
        _ => TransportOutcome::Retry {
            backoff_ms: d.retry_backoff_ms.clone().unwrap_or_default(),
            max_attempts: d.max_attempts.unwrap_or(0),
        },
    }
}

/// Classify a raw response into an actionable outcome. `client_error_count` is
/// the running count of prior non-auth 4xx (for the cumulative fail-fast rule).
pub fn classify_response(resp: &HttpResponse, client_error_count: i64) -> TransportOutcome {
    let decision = classify_http(resp.status, client_error_count, 0, 0);
    outcome_from_decision(&decision)
}

/// The 6 stable control-plane operations (§1.1). All synchronous.
///
/// Implementations return the raw [`HttpResponse`]; the engine classifies it
/// via [`classify_response`]. (Keeping classification out of the trait lets the
/// same FSM logic run identically against the fake and a real client.)
pub trait Transport: Send + Sync {
    /// `POST /internal/workers/register`.
    fn register(&self, worker_code: &str, body: &str) -> HttpResponse;
    /// `POST /internal/workers/{code}/heartbeat`.
    fn heartbeat(&self, worker_code: &str, body: &str) -> HttpResponse;
    /// `POST /internal/workers/{code}/deactivate`.
    fn deactivate(&self, worker_code: &str) -> HttpResponse;
    /// `POST /internal/tasks/{id}/claim`.
    fn claim(&self, task_id: &str, body: &str) -> HttpResponse;
    /// `POST /internal/tasks/{id}/report`.
    fn report(&self, task_id: &str, body: &str) -> HttpResponse;
    /// `POST /internal/tasks/{id}/renew`.
    fn renew(&self, task_id: &str, body: &str) -> HttpResponse;
}

/// Documented future adapter. std has **no** HTTP client and this crate is
/// zero-dependency, so a real implementation lives behind a feature/adapter
/// that brings its own client (reqwest / ureq / hyper). Calling any method here
/// panics by design — it exists to pin the trait surface and document the §1.1
/// HTTP-client requirements (keep-alive pool, custom `Idempotency-Key` header,
/// `timeout < heartbeat/3`).
///
/// # Not a working transport
/// **Every method panics (`unimplemented!`).** This type is an unimplemented
/// stub kept only to pin the [`Transport`] surface; do **not** wire it into a
/// worker. Use [`FakeTransport`] in tests, and a real client-backed adapter in
/// production. The `#[deprecated]` is a compile-time tripwire so a tenant cannot
/// accidentally construct/use it without a warning.
#[deprecated(
    note = "HttpTransport is an unimplemented stub — every method panics. Pins the \
            Transport trait surface only (待 reqwest/ureq adapter). Use FakeTransport \
            in tests or a real client-backed Transport in production; do not wire this in."
)]
#[derive(Debug, Clone)]
pub struct HttpTransport {
    pub base_url: String,
    /// Request timeout (ms). §1.1: must be < heartbeat interval / 3.
    pub timeout_ms: i64,
}

#[allow(deprecated)] // the stub's own impl legitimately names the deprecated type
impl HttpTransport {
    pub fn new(base_url: &str) -> Self {
        Self {
            base_url: base_url.to_string(),
            timeout_ms: 10_000,
        }
    }

    fn unimplemented(op: &str) -> HttpResponse {
        // A real adapter performs the call here. Phase 2 is std-only; never
        // invoked by the engine tests (which use FakeTransport).
        unimplemented!(
            "HttpTransport::{op} requires a real HTTP client adapter (zero-dependency phase-2 \
             ships only the trait + FakeTransport); see byo-sdk-guide §1.1"
        )
    }
}

#[allow(deprecated)] // the stub's own impl legitimately names the deprecated type
impl Transport for HttpTransport {
    fn register(&self, _worker_code: &str, _body: &str) -> HttpResponse {
        Self::unimplemented("register")
    }
    fn heartbeat(&self, _worker_code: &str, _body: &str) -> HttpResponse {
        Self::unimplemented("heartbeat")
    }
    fn deactivate(&self, _worker_code: &str) -> HttpResponse {
        Self::unimplemented("deactivate")
    }
    fn claim(&self, _task_id: &str, _body: &str) -> HttpResponse {
        Self::unimplemented("claim")
    }
    fn report(&self, _task_id: &str, _body: &str) -> HttpResponse {
        Self::unimplemented("report")
    }
    fn renew(&self, _task_id: &str, _body: &str) -> HttpResponse {
        Self::unimplemented("renew")
    }
}

/// A scripted, in-memory transport for tests. Each operation pops the next
/// queued [`HttpResponse`]; an empty queue yields a default 200. Records every
/// call so tests can assert call ordering (e.g. report-before-deactivate).
#[derive(Debug, Clone, Default)]
pub struct FakeTransport {
    inner: Arc<Mutex<FakeState>>,
}

/// The 6 control-plane operations, used to index the fake's scripted queues.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Op {
    Register,
    Heartbeat,
    Deactivate,
    Claim,
    Report,
    Renew,
}

impl Op {
    const COUNT: usize = 6;
    fn index(self) -> usize {
        self as usize
    }
    fn name(self) -> &'static str {
        match self {
            Op::Register => "register",
            Op::Heartbeat => "heartbeat",
            Op::Deactivate => "deactivate",
            Op::Claim => "claim",
            Op::Report => "report",
            Op::Renew => "renew",
        }
    }
}

#[derive(Debug)]
struct FakeState {
    /// One scripted-response queue per [`Op`], indexed by `Op::index`.
    queues: [VecDeque<HttpResponse>; Op::COUNT],
    /// Ordered log of operation names, in call order.
    calls: Vec<String>,
}

impl Default for FakeState {
    fn default() -> Self {
        Self {
            queues: Default::default(),
            calls: Vec::new(),
        }
    }
}

impl FakeTransport {
    pub fn new() -> Self {
        Self::default()
    }

    fn enqueue(&self, op: Op, r: HttpResponse) {
        let mut g = self.inner.lock().unwrap();
        g.queues[op.index()].push_back(r);
    }

    /// Queue a register response.
    pub fn queue_register(&self, r: HttpResponse) {
        self.enqueue(Op::Register, r);
    }
    pub fn queue_heartbeat(&self, r: HttpResponse) {
        self.enqueue(Op::Heartbeat, r);
    }
    pub fn queue_deactivate(&self, r: HttpResponse) {
        self.enqueue(Op::Deactivate, r);
    }
    pub fn queue_claim(&self, r: HttpResponse) {
        self.enqueue(Op::Claim, r);
    }
    pub fn queue_report(&self, r: HttpResponse) {
        self.enqueue(Op::Report, r);
    }
    pub fn queue_renew(&self, r: HttpResponse) {
        self.enqueue(Op::Renew, r);
    }

    /// The recorded call log, in order.
    pub fn call_log(&self) -> Vec<String> {
        self.inner.lock().unwrap().calls.clone()
    }

    fn take(&self, op: Op) -> HttpResponse {
        let mut g = self.inner.lock().unwrap();
        g.calls.push(op.name().to_string());
        g.queues[op.index()]
            .pop_front()
            .unwrap_or_else(|| HttpResponse::new(200, ""))
    }
}

impl Transport for FakeTransport {
    fn register(&self, _worker_code: &str, _body: &str) -> HttpResponse {
        self.take(Op::Register)
    }
    fn heartbeat(&self, _worker_code: &str, _body: &str) -> HttpResponse {
        self.take(Op::Heartbeat)
    }
    fn deactivate(&self, _worker_code: &str) -> HttpResponse {
        self.take(Op::Deactivate)
    }
    fn claim(&self, _task_id: &str, _body: &str) -> HttpResponse {
        self.take(Op::Claim)
    }
    fn report(&self, _task_id: &str, _body: &str) -> HttpResponse {
        self.take(Op::Report)
    }
    fn renew(&self, _task_id: &str, _body: &str) -> HttpResponse {
        self.take(Op::Renew)
    }
}

// NOTE on parsing: the engine reuses the phase-1 decision functions, which take
// typed structs (`HeartbeatResponse` / `RenewResponse`). Turning a raw response
// body into those structs is the future real adapter's job (JSON with
// ignore-unknown, §1.2); the scheduler tests construct the typed responses
// directly, so no JSON parser is needed in this std-only phase.

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn retry_outcome_from_5xx() {
        let t = FakeTransport::new();
        t.queue_report(HttpResponse::new(503, ""));
        let resp = t.report("task-1", "{}");
        match classify_response(&resp, 0) {
            TransportOutcome::Retry { backoff_ms, max_attempts } => {
                assert_eq!(backoff_ms, vec![200, 400, 800]);
                assert_eq!(max_attempts, 3);
            }
            other => panic!("expected Retry, got {other:?}"),
        }
    }

    #[test]
    fn retry_outcome_from_transport_error() {
        let resp = HttpResponse::transport_error();
        assert!(matches!(
            classify_response(&resp, 0),
            TransportOutcome::Retry { .. }
        ));
    }

    #[test]
    fn fatal_outcome_from_auth() {
        for status in [401, 403] {
            let resp = HttpResponse::new(status, "");
            assert_eq!(classify_response(&resp, 0), TransportOutcome::FailFast);
        }
    }

    #[test]
    fn idempotent_outcome_from_409() {
        let resp = HttpResponse::new(409, "");
        assert_eq!(
            classify_response(&resp, 0),
            TransportOutcome::IdempotentSuccess
        );
    }

    #[test]
    fn success_and_not_found() {
        assert_eq!(
            classify_response(&HttpResponse::new(200, ""), 0),
            TransportOutcome::Success
        );
        assert_eq!(
            classify_response(&HttpResponse::new(404, ""), 0),
            TransportOutcome::NotFound
        );
    }

    #[test]
    fn cumulative_4xx_fail_fast() {
        // 4 prior non-auth 4xx -> the 5th hits the threshold.
        let resp = HttpResponse::new(400, "");
        assert_eq!(classify_response(&resp, 4), TransportOutcome::FailFast);
        assert_eq!(classify_response(&resp, 0), TransportOutcome::ClientError);
    }

    #[test]
    fn fake_records_call_order() {
        let t = FakeTransport::new();
        t.report("t", "{}");
        t.deactivate("w");
        assert_eq!(t.call_log(), vec!["report".to_string(), "deactivate".to_string()]);
    }
}
