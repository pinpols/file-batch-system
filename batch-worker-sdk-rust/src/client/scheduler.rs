//! Heartbeat (§1.3) + lease-renewal (§1.4) schedulers, std-only and synchronous.
//!
//! Where the TS/Go reference uses async timers (`setInterval` / `time.Ticker` +
//! goroutine), this engine is synchronous and testable: each scheduler holds an
//! interval, a [`Transport`], a cancellation flag (`Arc<AtomicBool>`), and a
//! `tick()` method that performs exactly one cycle and returns the decision the
//! driver loop applies. A real driver spawns a `std::thread` that loops
//! `sleep(interval); tick()` until the cancel flag flips — but tests drive
//! `tick()` directly, so no sleeping/threads are needed to verify behavior.
//!
//! Mirrors the Java SDK `HeartbeatScheduler` / `LeaseRenewalScheduler`.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use crate::decide::{apply_heartbeat_directive, apply_renew, Decision};
use crate::client::transport::{classify_response, Transport, TransportOutcome};
use crate::protocol::{HeartbeatResponse, RenewResponse};

/// Default heartbeat interval (§1.3): 30s.
pub const DEFAULT_HEARTBEAT_INTERVAL_MS: i64 = 30_000;
/// Default lease-renewal interval (§1.4): 60s.
pub const DEFAULT_LEASE_RENEW_INTERVAL_MS: i64 = 60_000;

/// Outcome of one heartbeat tick: the applied [`Decision`] (FSM transition /
/// kafka directive / interval), or a transport-level signal.
#[derive(Debug, Clone, PartialEq)]
pub struct HeartbeatTick {
    /// `apply_heartbeat_directive` result, when the call succeeded (2xx).
    pub decision: Option<Decision>,
    /// The interval to use for the *next* sleep (dynamically updated from
    /// `nextHeartbeatHint`; otherwise unchanged).
    pub next_interval_ms: i64,
    /// Transport classification (e.g. FailFast on auth error).
    pub transport: TransportOutcome,
}

/// Heartbeat scheduler. Interval + transport are injected. The interval is
/// **dynamically updated** from `nextHeartbeatHint` each successful tick (§1.3).
pub struct HeartbeatScheduler<T: Transport> {
    pub worker_code: String,
    pub interval_ms: i64,
    transport: T,
    cancel: Arc<AtomicBool>,
}

impl<T: Transport> HeartbeatScheduler<T> {
    /// New scheduler at the default 30s interval.
    pub fn new(worker_code: &str, transport: T) -> Self {
        Self::with_interval(worker_code, transport, DEFAULT_HEARTBEAT_INTERVAL_MS)
    }

    /// New scheduler with an injected interval.
    pub fn with_interval(worker_code: &str, transport: T, interval_ms: i64) -> Self {
        Self {
            worker_code: worker_code.to_string(),
            interval_ms,
            transport,
            cancel: Arc::new(AtomicBool::new(false)),
        }
    }

    /// The shared cancel flag (a signal handler / lifecycle flips it).
    pub fn cancel_flag(&self) -> Arc<AtomicBool> {
        Arc::clone(&self.cancel)
    }

    /// Signal this scheduler to stop after the current tick.
    pub fn signal_cancel(&self) {
        self.cancel.store(true, Ordering::SeqCst);
    }

    pub fn is_cancelled(&self) -> bool {
        self.cancel.load(Ordering::SeqCst)
    }

    /// Perform exactly one heartbeat cycle.
    ///
    /// The caller supplies the parsed [`HeartbeatResponse`] *and* the raw status
    /// (the real adapter parses the body the transport returned; tests pass a
    /// typed response + status directly). On a 2xx, the directive is applied via
    /// the phase-1 [`apply_heartbeat_directive`] and the interval is bumped from
    /// any `nextHeartbeatHint`.
    pub fn tick(&mut self, body: &str) -> HttpTickRaw {
        let resp = self.transport.heartbeat(&self.worker_code, body);
        HttpTickRaw { status: resp.status, body: resp.body }
    }

    /// Apply a parsed heartbeat response (separating IO from decision keeps this
    /// std-only-testable: the real adapter parses `tick()`'s raw body into
    /// `HeartbeatResponse`, then calls this).
    pub fn apply(&mut self, status: i64, parsed: &HeartbeatResponse) -> HeartbeatTick {
        let transport = classify_response(
            &crate::client::transport::HttpResponse::new(status, ""),
            0,
        );
        if let TransportOutcome::Success = transport {
            let decision = apply_heartbeat_directive(parsed);
            if let Some(ms) = decision.heartbeat_next_interval_ms {
                // §1.3: dynamic speed adjustment from nextHeartbeatHint.
                self.interval_ms = ms;
            }
            HeartbeatTick {
                next_interval_ms: self.interval_ms,
                decision: Some(decision),
                transport,
            }
        } else {
            HeartbeatTick {
                next_interval_ms: self.interval_ms,
                decision: None,
                transport,
            }
        }
    }
}

/// Raw output of a heartbeat IO tick (status + body), to be parsed by the
/// adapter then fed to [`HeartbeatScheduler::apply`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HttpTickRaw {
    pub status: i64,
    pub body: String,
}

/// A renewal decision for one in-flight task.
#[derive(Debug, Clone, PartialEq)]
pub struct RenewTick {
    pub task_id: String,
    /// `apply_renew` decision (cancel vs none) when the call returned 2xx.
    pub decision: Option<Decision>,
    /// True when the task should be dropped locally (404/409 — lease reclaimed).
    pub drop_local: bool,
    pub transport: TransportOutcome,
}

/// Lease-renewal scheduler. Default 60s; iterates in-flight tasks each cycle and
/// signals cancellation per the renew response (§1.4).
pub struct LeaseRenewalScheduler<T: Transport> {
    pub interval_ms: i64,
    transport: T,
    cancel: Arc<AtomicBool>,
}

impl<T: Transport> LeaseRenewalScheduler<T> {
    pub fn new(transport: T) -> Self {
        Self::with_interval(transport, DEFAULT_LEASE_RENEW_INTERVAL_MS)
    }

    pub fn with_interval(transport: T, interval_ms: i64) -> Self {
        Self {
            interval_ms,
            transport,
            cancel: Arc::new(AtomicBool::new(false)),
        }
    }

    pub fn cancel_flag(&self) -> Arc<AtomicBool> {
        Arc::clone(&self.cancel)
    }
    pub fn signal_cancel(&self) {
        self.cancel.store(true, Ordering::SeqCst);
    }
    pub fn is_cancelled(&self) -> bool {
        self.cancel.load(Ordering::SeqCst)
    }

    /// Renew a single in-flight task. The adapter parses the renew body into a
    /// [`RenewResponse`]; tests pass it directly with the status.
    pub fn renew_one(&self, task_id: &str, status: i64, parsed: &RenewResponse) -> RenewTick {
        let transport = classify_response(
            &crate::client::transport::HttpResponse::new(status, ""),
            0,
        );
        match transport {
            TransportOutcome::Success => {
                let decision = apply_renew(parsed);
                RenewTick {
                    task_id: task_id.to_string(),
                    decision: Some(decision),
                    drop_local: false,
                    transport,
                }
            }
            // 404 (NotFound) / 409 (IdempotentSuccess) -> lease reclaimed; drop
            // the task locally — even if the handler finishes it cannot report.
            TransportOutcome::NotFound | TransportOutcome::IdempotentSuccess => RenewTick {
                task_id: task_id.to_string(),
                decision: None,
                drop_local: true,
                transport,
            },
            other => RenewTick {
                task_id: task_id.to_string(),
                decision: None,
                drop_local: false,
                transport: other,
            },
        }
    }

    /// Perform the raw renew IO for a task (status + body for the adapter).
    pub fn tick(&self, task_id: &str, body: &str) -> HttpTickRaw {
        let resp = self.transport.renew(task_id, body);
        HttpTickRaw { status: resp.status, body: resp.body }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::client::transport::{FakeTransport, HttpResponse};
    use crate::protocol::HeartbeatHint;

    #[test]
    fn heartbeat_default_interval_is_30s() {
        let s = HeartbeatScheduler::new("w1", FakeTransport::new());
        assert_eq!(s.interval_ms, DEFAULT_HEARTBEAT_INTERVAL_MS);
    }

    #[test]
    fn heartbeat_applies_draining_directive() {
        let t = FakeTransport::new();
        t.queue_heartbeat(HttpResponse::new(200, "{}"));
        let mut s = HeartbeatScheduler::new("w1", t);
        let _raw = s.tick("{}");
        let parsed = HeartbeatResponse {
            platform_status: Some("DRAINING".to_string()),
            ..Default::default()
        };
        let tick = s.apply(200, &parsed);
        let d = tick.decision.expect("2xx -> decision");
        assert_eq!(d.fsm_transition.as_deref(), Some("DRAINING"));
        assert_eq!(d.kafka.as_deref(), Some("pause"));
        assert_eq!(d.drain_then_deactivate, Some(true));
    }

    #[test]
    fn heartbeat_dynamic_interval_pt15s_to_15000() {
        let mut s = HeartbeatScheduler::new("w1", FakeTransport::new());
        let parsed = HeartbeatResponse {
            next_heartbeat_hint: Some(HeartbeatHint::Iso("PT15S".to_string())),
            ..Default::default()
        };
        let tick = s.apply(200, &parsed);
        assert_eq!(tick.next_interval_ms, 15_000);
        // Interval is now persisted for the next sleep.
        assert_eq!(s.interval_ms, 15_000);
    }

    #[test]
    fn heartbeat_auth_error_is_fail_fast_no_decision() {
        let mut s = HeartbeatScheduler::new("w1", FakeTransport::new());
        let tick = s.apply(401, &HeartbeatResponse::default());
        assert_eq!(tick.transport, TransportOutcome::FailFast);
        assert!(tick.decision.is_none());
        // Interval unchanged on a failed heartbeat.
        assert_eq!(tick.next_interval_ms, DEFAULT_HEARTBEAT_INTERVAL_MS);
    }

    #[test]
    fn lease_default_interval_is_60s() {
        let s = LeaseRenewalScheduler::new(FakeTransport::new());
        assert_eq!(s.interval_ms, DEFAULT_LEASE_RENEW_INTERVAL_MS);
    }

    #[test]
    fn renew_cancel_requested_signals_cancel() {
        let s = LeaseRenewalScheduler::new(FakeTransport::new());
        let parsed = RenewResponse {
            cancel_requested: Some(true),
            ..Default::default()
        };
        let tick = s.renew_one("task-1", 200, &parsed);
        let d = tick.decision.expect("2xx -> decision");
        assert_eq!(d.action, "cancel");
        assert_eq!(d.cancel_requested, Some(true));
        assert!(!tick.drop_local);
    }

    #[test]
    fn renew_404_drops_task_locally() {
        let s = LeaseRenewalScheduler::new(FakeTransport::new());
        let tick = s.renew_one("task-1", 404, &RenewResponse::default());
        assert!(tick.drop_local);
        assert!(tick.decision.is_none());
    }

    #[test]
    fn renew_409_drops_task_locally() {
        let s = LeaseRenewalScheduler::new(FakeTransport::new());
        let tick = s.renew_one("task-1", 409, &RenewResponse::default());
        assert!(tick.drop_local);
    }

    #[test]
    fn cancel_flag_signals() {
        let s = HeartbeatScheduler::new("w1", FakeTransport::new());
        assert!(!s.is_cancelled());
        s.signal_cancel();
        assert!(s.is_cancelled());
    }
}
