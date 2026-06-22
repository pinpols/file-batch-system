//! Worker lifecycle (§1.5 4-state FSM + §1.6 graceful stop), std-only.
//!
//! Mirrors the Java SDK `BatchPlatformClient` start/stop and the TS/Go phase-2
//! lifecycle. The 4 states map 1:1 to `WorkerHeartbeatResponse.platformStatus`
//! (and to [`crate::constants::WORKER_RUNTIME_STATES`]).
//!
//! ## stop(timeout) sequence (§1.6)
//! 1. set `draining = true` (reject new Kafka messages immediately),
//! 2. wake the consumer (`kafka.wakeup()` — interrupt poll),
//! 3. drain in-flight tasks (wait up to `timeout * 0.4`),
//! 4. shut the executor (wait up to `timeout * 0.6`),
//! 5. `POST /internal/workers/{code}/deactivate` (say goodbye).
//!
//! The decision content of step 1-5 is the phase-1 [`plan_stop`]; this module
//! sequences it and records the ordered steps so tests can assert ordering
//! without real threads/sockets.
//!
//! ## SIGTERM
//! std has only limited signal handling and no portable async-signal-safe hook,
//! so the documented integration point is [`Worker::request_stop`]: a tenant's
//! SIGTERM handler (e.g. `signal_hook` crate, or a raw `libc::sigaction` in the
//! tenant binary) calls `request_stop()`, and the main loop observes the flag
//! and invokes `stop(30_000)` within K8s' 30s grace window.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use crate::decide::{decide_register, plan_stop, Decision};
use crate::client::transport::{classify_response, Transport, TransportOutcome};

/// The 4 worker runtime states (§1.5). Order/spelling match
/// [`crate::constants::WORKER_RUNTIME_STATES`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WorkerState {
    /// Assignment active, normal claiming.
    Normal,
    /// Assignment active, reduced concurrency / quieter logs.
    Degraded,
    /// Assignment paused; in-flight keep running, no new messages.
    Paused,
    /// Assignment paused; drain in-flight then deactivate + exit.
    Draining,
}

impl WorkerState {
    /// The canonical platform-status string for this state.
    pub fn as_str(self) -> &'static str {
        match self {
            WorkerState::Normal => "NORMAL",
            WorkerState::Degraded => "DEGRADED",
            WorkerState::Paused => "PAUSED",
            WorkerState::Draining => "DRAINING",
        }
    }

    /// Parse a platform-status string; unknown values leave the state unchanged
    /// (the caller passes the current state as fallback).
    pub fn from_status(status: &str, current: WorkerState) -> WorkerState {
        match status {
            "NORMAL" => WorkerState::Normal,
            "DEGRADED" => WorkerState::Degraded,
            "PAUSED" => WorkerState::Paused,
            "DRAINING" => WorkerState::Draining,
            _ => current,
        }
    }

    /// Whether new Kafka messages should be accepted in this state.
    pub fn accepts_new_messages(self) -> bool {
        matches!(self, WorkerState::Normal | WorkerState::Degraded)
    }
}

/// The ordered steps of a graceful stop, recorded for assertion.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StopStep {
    /// `draining = true` — reject new messages.
    EnterDraining,
    /// `kafka.wakeup()` — interrupt the consumer poll.
    WakeConsumer,
    /// Drain in-flight (budget = timeout * 0.4 ms).
    DrainInFlight { budget_ms: i64 },
    /// Executor shutdown (budget = timeout * 0.6 ms).
    ShutdownExecutor { budget_ms: i64 },
    /// `POST /deactivate`.
    Deactivate,
}

/// Result of a `stop()` call: the ordered steps performed + the stop plan + the
/// deactivate transport outcome.
#[derive(Debug, Clone)]
pub struct StopReport {
    pub steps: Vec<StopStep>,
    pub plan: Decision,
    pub deactivate_outcome: TransportOutcome,
}

/// The worker: owns FSM state, the draining flag, a stop-request flag (SIGTERM
/// hook), and the transport.
pub struct Worker<T: Transport> {
    pub worker_code: String,
    state: WorkerState,
    draining: Arc<AtomicBool>,
    stop_requested: Arc<AtomicBool>,
    transport: T,
    started: bool,
}

impl<T: Transport> Worker<T> {
    pub fn new(worker_code: &str, transport: T) -> Self {
        Self {
            worker_code: worker_code.to_string(),
            state: WorkerState::Normal,
            draining: Arc::new(AtomicBool::new(false)),
            stop_requested: Arc::new(AtomicBool::new(false)),
            transport,
            started: false,
        }
    }

    pub fn state(&self) -> WorkerState {
        self.state
    }

    pub fn is_draining(&self) -> bool {
        self.draining.load(Ordering::SeqCst)
    }

    pub fn is_started(&self) -> bool {
        self.started
    }

    /// Apply a platform-status directive (from a heartbeat) to the FSM.
    pub fn apply_state(&mut self, status: &str) {
        self.state = WorkerState::from_status(status, self.state);
        if self.state == WorkerState::Draining {
            self.draining.store(true, Ordering::SeqCst);
        }
    }

    /// SIGTERM hook: a tenant signal handler calls this; the main loop polls
    /// [`Worker::stop_requested`] and then runs `stop()`.
    pub fn request_stop(&self) {
        self.stop_requested.store(true, Ordering::SeqCst);
    }

    pub fn stop_requested(&self) -> bool {
        self.stop_requested.load(Ordering::SeqCst)
    }

    /// Shared stop-request flag (so a signal handler thread can hold a clone).
    pub fn stop_flag(&self) -> Arc<AtomicBool> {
        Arc::clone(&self.stop_requested)
    }

    /// `start()` — register with the platform and transition to NORMAL.
    /// Returns the [`decide_register`] decision (schedulers to start, kafka
    /// subscribe). On a non-2xx register the worker fails fast and stays
    /// un-started.
    pub fn start(&mut self, register_body: &str, idempotent: bool) -> Result<Decision, TransportOutcome> {
        let resp = self.transport.register(&self.worker_code, register_body);
        match classify_response(&resp, 0) {
            TransportOutcome::Success | TransportOutcome::IdempotentSuccess => {
                self.state = WorkerState::Normal;
                self.started = true;
                Ok(decide_register(idempotent))
            }
            other => Err(other),
        }
    }

    /// `stop(timeout_ms)` — the §1.6 graceful sequence. Records each step in
    /// order, then deactivates. Budgets follow Java SDK: drain = 40%, executor
    /// shutdown = 60% of the timeout.
    pub fn stop(&mut self, timeout_ms: i64) -> StopReport {
        let plan = plan_stop(timeout_ms);
        let mut steps = Vec::new();

        // 1. enter draining (reject new messages immediately).
        self.draining.store(true, Ordering::SeqCst);
        self.state = WorkerState::Draining;
        steps.push(StopStep::EnterDraining);

        // 2. wake the consumer (interrupt poll). plan.kafka == "wakeup".
        steps.push(StopStep::WakeConsumer);

        // 3. drain in-flight (40% budget).
        let drain_budget = ((timeout_ms as f64) * 0.4) as i64;
        steps.push(StopStep::DrainInFlight { budget_ms: drain_budget });

        // 4. shut executor (60% budget).
        let exec_budget = ((timeout_ms as f64) * 0.6) as i64;
        steps.push(StopStep::ShutdownExecutor { budget_ms: exec_budget });

        // 5. deactivate (say goodbye).
        let resp = self.transport.deactivate(&self.worker_code);
        let deactivate_outcome = classify_response(&resp, 0);
        steps.push(StopStep::Deactivate);

        self.started = false;

        StopReport {
            steps,
            plan,
            deactivate_outcome,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::client::transport::{FakeTransport, HttpResponse};

    #[test]
    fn fsm_transitions_from_status() {
        let mut w = Worker::new("w1", FakeTransport::new());
        assert_eq!(w.state(), WorkerState::Normal);
        w.apply_state("DEGRADED");
        assert_eq!(w.state(), WorkerState::Degraded);
        w.apply_state("PAUSED");
        assert_eq!(w.state(), WorkerState::Paused);
        assert!(!w.is_draining());
        w.apply_state("DRAINING");
        assert_eq!(w.state(), WorkerState::Draining);
        assert!(w.is_draining());
    }

    #[test]
    fn unknown_status_keeps_current_state() {
        let mut w = Worker::new("w1", FakeTransport::new());
        w.apply_state("DEGRADED");
        w.apply_state("WAT");
        assert_eq!(w.state(), WorkerState::Degraded);
    }

    #[test]
    fn accepts_messages_only_in_active_states() {
        assert!(WorkerState::Normal.accepts_new_messages());
        assert!(WorkerState::Degraded.accepts_new_messages());
        assert!(!WorkerState::Paused.accepts_new_messages());
        assert!(!WorkerState::Draining.accepts_new_messages());
    }

    #[test]
    fn start_registers_and_transitions_normal() {
        let w_transport = FakeTransport::new();
        w_transport.queue_register(HttpResponse::new(200, "{}"));
        let mut w = Worker::new("w1", w_transport);
        let d = w.start("{}", false).expect("register ok");
        assert!(w.is_started());
        assert_eq!(w.state(), WorkerState::Normal);
        assert_eq!(d.action, "register-online");
        assert_eq!(
            d.start_schedulers,
            Some(vec!["heartbeat".to_string(), "leaseRenew".to_string()])
        );
    }

    #[test]
    fn start_fail_fast_on_auth_error() {
        let t = FakeTransport::new();
        t.queue_register(HttpResponse::new(403, ""));
        let mut w = Worker::new("w1", t);
        let err = w.start("{}", false).unwrap_err();
        assert_eq!(err, TransportOutcome::FailFast);
        assert!(!w.is_started());
    }

    #[test]
    fn stop_sequence_ordering() {
        let t = FakeTransport::new();
        t.queue_deactivate(HttpResponse::new(200, ""));
        let mut w = Worker::new("w1", t);
        let report = w.stop(30_000);

        assert_eq!(
            report.steps,
            vec![
                StopStep::EnterDraining,
                StopStep::WakeConsumer,
                StopStep::DrainInFlight { budget_ms: 12_000 },
                StopStep::ShutdownExecutor { budget_ms: 18_000 },
                StopStep::Deactivate,
            ]
        );
        assert_eq!(report.plan.action, "graceful-stop");
        assert_eq!(report.plan.within_ms, Some(30_000));
        assert_eq!(report.deactivate_outcome, TransportOutcome::Success);
        assert_eq!(w.state(), WorkerState::Draining);
        assert!(w.is_draining());
    }

    #[test]
    fn stop_calls_deactivate_last_on_transport() {
        // Deactivate must be the only transport call during stop, and it must
        // come after the drain/shutdown steps.
        let t = FakeTransport::new();
        t.queue_deactivate(HttpResponse::new(200, ""));
        let log_handle = t.clone();
        let mut w = Worker::new("w1", t);
        w.stop(10_000);
        assert_eq!(log_handle.call_log(), vec!["deactivate".to_string()]);
    }

    #[test]
    fn sigterm_request_stop_sets_flag() {
        let w = Worker::new("w1", FakeTransport::new());
        assert!(!w.stop_requested());
        let flag = w.stop_flag();
        // Simulate a signal handler on another thread flipping the shared flag.
        flag.store(true, Ordering::SeqCst);
        assert!(w.stop_requested());
    }
}
