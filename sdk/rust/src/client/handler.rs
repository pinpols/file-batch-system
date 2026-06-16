//! Handler SPI — the surface a tenant implements to execute a task.
//!
//! Mirrors the Java SDK `TaskHandler` / `SdkTaskContext` contract and the
//! TS/Go phase-2 handler module. All synchronous + std-only.
//!
//! ## JSON field names (wire-protocol §B, report body)
//! [`TaskResult`] serializes to the canonical report payload. The field names
//! are **load-bearing** (the platform rejects misnamed fields):
//! * `errorCode`     — one of [`crate::protocol::error_code`]
//! * `outputs`       — arbitrary key/value output artifacts
//! * `resultSummary` — human-readable summary string
//!
//! Rust structs use snake_case fields (`error_code`, `outputs`,
//! `result_summary`); the future real transport maps them to the camelCase wire
//! names above. The mapping is documented here and asserted by the testkit.

use std::collections::BTreeMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use crate::protocol::error_code;

/// Cooperative cancellation signal handed to a running handler.
///
/// The lease-renewal scheduler flips this to `true` when the platform reports
/// `cancelRequested`; a well-behaved handler polls [`CancellationSignal::is_cancelled`]
/// and returns promptly. Backed by an `Arc<AtomicBool>` so it can be shared
/// across the renewal thread and the handler thread.
#[derive(Debug, Clone, Default)]
pub struct CancellationSignal {
    flag: Arc<AtomicBool>,
}

impl CancellationSignal {
    pub fn new() -> Self {
        Self {
            flag: Arc::new(AtomicBool::new(false)),
        }
    }

    /// Construct from an existing shared flag (used by the scheduler so it and
    /// the handler observe the same atomic).
    pub fn from_flag(flag: Arc<AtomicBool>) -> Self {
        Self { flag }
    }

    /// Request cancellation (called by the renewal scheduler / signal handler).
    pub fn cancel(&self) {
        self.flag.store(true, Ordering::SeqCst);
    }

    /// True once cancellation has been requested.
    pub fn is_cancelled(&self) -> bool {
        self.flag.load(Ordering::SeqCst)
    }

    /// The underlying shared flag (so the scheduler can keep a handle).
    pub fn flag(&self) -> Arc<AtomicBool> {
        Arc::clone(&self.flag)
    }
}

/// Progress reporting hook. A handler calls [`ProgressReporter::report_progress`]
/// to push intermediate progress (0..=100) + an optional message. The real
/// transport forwards this to the platform; the fake records it.
pub trait ProgressReporter: Send {
    fn report_progress(&self, percent: u8, message: &str);
}

/// A no-op progress reporter (default when the tenant does not care).
#[derive(Debug, Default, Clone)]
pub struct NoopProgressReporter;

impl ProgressReporter for NoopProgressReporter {
    fn report_progress(&self, _percent: u8, _message: &str) {}
}

/// Per-task execution context handed to a [`TaskHandler`].
///
/// Carries the immutable task identity + the effective config snapshot (from
/// `/internal/tasks/{id}/claim`), the cancellation signal, and a progress
/// reporter. Mirrors `SdkTaskContext` in the Java SDK.
pub struct TaskContext {
    pub task_id: String,
    pub tenant_id: String,
    pub task_type: String,
    /// `partitionInvocationId` — threaded through to the claim/renew/report
    /// bodies the lease lifecycle builds (ADR-014, fixture 10). `None` when the
    /// dispatch message omits it.
    pub partition_invocation_id: Option<String>,
    /// `runtimeAttributes.traceId` passthrough (OTel link), empty if absent.
    pub trace_id: String,
    /// Effective task config / business parameters (claim response snapshot).
    pub parameters: BTreeMap<String, String>,
    pub cancellation: CancellationSignal,
    pub progress: Box<dyn ProgressReporter>,
}

impl TaskContext {
    /// Convenience constructor with a no-op progress reporter and fresh signal.
    pub fn new(task_id: &str, tenant_id: &str, task_type: &str) -> Self {
        Self {
            task_id: task_id.to_string(),
            tenant_id: tenant_id.to_string(),
            task_type: task_type.to_string(),
            partition_invocation_id: None,
            trace_id: String::new(),
            parameters: BTreeMap::new(),
            cancellation: CancellationSignal::new(),
            progress: Box::new(NoopProgressReporter),
        }
    }

    /// Builder-style: attach the `partitionInvocationId` so the lease lifecycle
    /// can include it in claim/renew/report bodies.
    pub fn with_partition_invocation_id(mut self, id: Option<String>) -> Self {
        self.partition_invocation_id = id;
        self
    }

    /// Shorthand for `self.cancellation.is_cancelled()`.
    pub fn is_cancelled(&self) -> bool {
        self.cancellation.is_cancelled()
    }
}

/// The result a handler returns. Field names documented above map to the wire
/// `errorCode` / `outputs` / `resultSummary`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TaskResult {
    /// Wire `errorCode` — `SUCCESS` on the happy path.
    pub error_code: String,
    /// Wire `outputs` — produced artifacts (file refs, counts, ...).
    pub outputs: BTreeMap<String, String>,
    /// Wire `resultSummary` — human-readable summary.
    pub result_summary: String,
}

impl TaskResult {
    /// A success result (`errorCode = SUCCESS`).
    pub fn success(summary: &str) -> Self {
        Self {
            error_code: error_code::SUCCESS.to_string(),
            outputs: BTreeMap::new(),
            result_summary: summary.to_string(),
        }
    }

    /// A failure result with an explicit error code (§B vocabulary).
    pub fn failure(code: &str, summary: &str) -> Self {
        Self {
            error_code: code.to_string(),
            outputs: BTreeMap::new(),
            result_summary: summary.to_string(),
        }
    }

    /// A cancelled result (`errorCode = CANCELLED`).
    pub fn cancelled(summary: &str) -> Self {
        Self::failure(error_code::CANCELLED, summary)
    }

    /// True when this result reports success.
    pub fn is_success(&self) -> bool {
        self.error_code == error_code::SUCCESS
    }

    /// Attach an output entry (builder-style).
    pub fn with_output(mut self, key: &str, value: &str) -> Self {
        self.outputs.insert(key.to_string(), value.to_string());
        self
    }
}

/// The SPI a tenant implements: execute a single task to completion.
///
/// Implementations should poll `ctx.is_cancelled()` for long-running work and
/// return a [`TaskResult::cancelled`] when cancellation is observed.
pub trait TaskHandler: Send {
    /// The task type this handler serves (used for routing / `pausedTaskTypes`).
    fn task_type(&self) -> &str;

    /// Execute the task. Must not panic on cancellation — return promptly.
    fn execute(&self, ctx: &TaskContext) -> TaskResult;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn task_result_success_and_codes() {
        let ok = TaskResult::success("done");
        assert!(ok.is_success());
        assert_eq!(ok.error_code, "SUCCESS");

        let fail = TaskResult::failure(error_code::EXECUTION_FAILED, "boom");
        assert!(!fail.is_success());
        assert_eq!(fail.error_code, "EXECUTION_FAILED");

        let cancelled = TaskResult::cancelled("stopped");
        assert_eq!(cancelled.error_code, "CANCELLED");
    }

    #[test]
    fn cancellation_signal_shared_via_flag() {
        let sig = CancellationSignal::new();
        let mirror = CancellationSignal::from_flag(sig.flag());
        assert!(!mirror.is_cancelled());
        sig.cancel();
        // Both observe the same atomic.
        assert!(mirror.is_cancelled());
    }

    /// A handler that runs until cancelled, then returns CANCELLED.
    struct CancellableHandler;
    impl TaskHandler for CancellableHandler {
        fn task_type(&self) -> &str {
            "demo"
        }
        fn execute(&self, ctx: &TaskContext) -> TaskResult {
            // Simulate a long loop that checks the signal each iteration.
            for _ in 0..1_000_000 {
                if ctx.is_cancelled() {
                    return TaskResult::cancelled("observed cancel");
                }
            }
            TaskResult::success("ran to completion")
        }
    }

    #[test]
    fn handler_observes_cancellation() {
        let mut ctx = TaskContext::new("t1", "tenant-a", "demo");
        // Pre-cancel so the very first poll trips.
        ctx.cancellation.cancel();
        let handler = CancellableHandler;
        let result = handler.execute(&ctx);
        assert_eq!(result.error_code, "CANCELLED");
        assert_eq!(result.result_summary, "observed cancel");
    }

    #[test]
    fn handler_runs_to_completion_when_not_cancelled() {
        let ctx = TaskContext::new("t2", "tenant-a", "demo");
        let result = CancellableHandler.execute(&ctx);
        assert!(result.is_success());
    }

    #[test]
    fn result_with_output_builder() {
        let r = TaskResult::success("ok").with_output("file", "s3://x");
        assert_eq!(r.outputs.get("file").map(String::as_str), Some("s3://x"));
    }
}
