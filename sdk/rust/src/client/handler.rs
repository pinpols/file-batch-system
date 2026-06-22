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
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;

use crate::client::checkpoint::{
    BreakPosition, InMemoryCheckpoint, SdkCheckpoint, SdkCheckpointState, SdkTaskStopped,
};
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
///
/// ADR-037 P1–P3: it also carries the checkpoint/resume + reliable-commit
/// primitives — [`checkpoint()`](TaskContext::checkpoint) for load/save and
/// [`commit()`](TaskContext::commit) for the three-in-one batch commit.
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
    /// ADR-037 决策一 — the checkpoint store backing
    /// [`checkpoint()`](TaskContext::checkpoint). Behind an `Arc` so the (real)
    /// store can be shared across the std::thread worker model. Defaults to an
    /// [`InMemoryCheckpoint`]; a real handler injects its own transactional impl.
    pub checkpoint: Arc<dyn SdkCheckpoint>,
    /// ADR-037 决策二 — report every Nth `commit` (counter % `report_interval`).
    /// `1` reports every batch; a larger value rate-limits progress IO. `0` is
    /// treated as `1` (always report) to avoid a divide-by-zero.
    pub report_interval: u64,
    /// ADR-037 决策二 — when `true`, `commit` skips its automatic
    /// `report_progress` and the handler controls progress reporting itself.
    pub self_report: bool,
    /// Internal commit counter driving the report-interval rate limit.
    commit_counter: Arc<AtomicU64>,
}

impl TaskContext {
    /// Convenience constructor with a no-op progress reporter and fresh signal.
    ///
    /// Defaults the ADR-037 primitives to an in-memory checkpoint store,
    /// `report_interval = 1` (report every commit), and `self_report = false`.
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
            checkpoint: Arc::new(InMemoryCheckpoint::new()),
            report_interval: 1,
            self_report: false,
            commit_counter: Arc::new(AtomicU64::new(0)),
        }
    }

    /// Builder-style: attach the `partitionInvocationId` so the lease lifecycle
    /// can include it in claim/renew/report bodies.
    pub fn with_partition_invocation_id(mut self, id: Option<String>) -> Self {
        self.partition_invocation_id = id;
        self
    }

    /// Builder-style: inject a tenant's [`SdkCheckpoint`] implementation.
    pub fn with_checkpoint(mut self, checkpoint: Arc<dyn SdkCheckpoint>) -> Self {
        self.checkpoint = checkpoint;
        self
    }

    /// Builder-style: set the progress report interval (commits per report).
    pub fn with_report_interval(mut self, interval: u64) -> Self {
        self.report_interval = interval;
        self
    }

    /// Builder-style: enable handler-controlled progress reporting (`commit`
    /// stops auto-reporting).
    pub fn with_self_report(mut self, self_report: bool) -> Self {
        self.self_report = self_report;
        self
    }

    /// Shorthand for `self.cancellation.is_cancelled()`.
    pub fn is_cancelled(&self) -> bool {
        self.cancellation.is_cancelled()
    }

    /// ADR-037 决策一 — the checkpoint store for load/resume + save.
    ///
    /// A handler calls this at `execute` start to `load()` the last
    /// [`SdkCheckpointState`] (skip when `completed`, resume from
    /// `break_position`, restore counters), and `commit()` drives `save()`.
    pub fn checkpoint(&self) -> &Arc<dyn SdkCheckpoint> {
        &self.checkpoint
    }

    /// ADR-037 决策二/三 — commit one business batch (three-in-one) + cooperative
    /// cancel safe-point.
    ///
    /// Performs, in order:
    /// 1. **Save the checkpoint** (决策二): persists `succeed_count` /
    ///    `fail_count` / `break_position` via [`SdkCheckpoint::save`]. The
    ///    same-transaction强约束 (atomic with the business write) is the tenant
    ///    impl's responsibility — see [`crate::client::checkpoint`].
    /// 2. **Rate-limited progress report** (决策二): increments the commit
    ///    counter and, when `counter % report_interval == 0` (and `self_report`
    ///    is off), calls [`ProgressReporter::report_progress`]. `report_interval
    ///    == 0` is treated as `1`.
    /// 3. **Cooperative cancel check** (决策三): if [`is_cancelled`] is now true,
    ///    returns `Err(`[`SdkTaskStopped`]`)` carrying the just-committed
    ///    `break_position`. The cancel is observed only at this clean batch
    ///    boundary, so no half-batch dirty data is left behind. The handler must
    ///    propagate the error up; the run path maps it to a **cancelled**
    ///    terminal report.
    ///
    /// Returns `Err(SdkTaskStopped)` only for cancellation. A checkpoint backend
    /// failure surfaces as a panic-free path only if the tenant's `save` is
    /// fallible — here a `save` error is mapped into a stop is *not* done;
    /// instead the error is logged-and-ignored is *not* done either: a failed
    /// `save` means the batch is not durably checkpointed, so we propagate it as
    /// a stopped-at-previous-safe-point is wrong too. We therefore surface a
    /// `save` failure by **not** advancing: the caller gets the original
    /// [`CheckpointError`]-shaped outcome via [`try_commit`](TaskContext::try_commit).
    ///
    /// This `commit` convenience assumes the checkpoint `save` succeeds (the
    /// in-memory default never fails); use [`try_commit`](TaskContext::try_commit)
    /// when the backing store is fallible and you must react to a save error.
    pub fn commit(
        &self,
        succeed_count: i64,
        fail_count: i64,
        break_position: BreakPosition,
    ) -> Result<(), SdkTaskStopped> {
        let state = SdkCheckpointState {
            break_position: break_position.clone(),
            succeed_count,
            fail_count,
            completed: false,
        };
        // 1. Save checkpoint. The in-memory default is infallible; a fallible
        //    tenant store should use `try_commit` to observe save errors. Here a
        //    save error is dropped to keep the cancel-only Result shape, so this
        //    path is intended for the infallible/dev store.
        let _ = self.checkpoint.save(&self.task_id, &state);
        self.report_and_check_cancel(succeed_count, fail_count, break_position)
    }

    /// Fallible variant of [`commit`](TaskContext::commit) for real (DB/KV)
    /// stores: propagates a [`SdkCheckpoint::save`] error so the handler can
    /// retry/fail rather than silently losing the checkpoint, while still
    /// returning [`SdkTaskStopped`] on cooperative cancel.
    pub fn try_commit(
        &self,
        succeed_count: i64,
        fail_count: i64,
        break_position: BreakPosition,
    ) -> Result<(), CommitError> {
        let state = SdkCheckpointState {
            break_position: break_position.clone(),
            succeed_count,
            fail_count,
            completed: false,
        };
        self.checkpoint
            .save(&self.task_id, &state)
            .map_err(CommitError::Checkpoint)?;
        self.report_and_check_cancel(succeed_count, fail_count, break_position)
            .map_err(CommitError::Stopped)
    }

    /// Shared tail of `commit` / `try_commit`: rate-limited progress report then
    /// the cooperative-cancel check.
    fn report_and_check_cancel(
        &self,
        succeed_count: i64,
        fail_count: i64,
        break_position: BreakPosition,
    ) -> Result<(), SdkTaskStopped> {
        // 2. Rate-limited progress report (搭 commit 的车).
        let counter = self.commit_counter.fetch_add(1, Ordering::SeqCst) + 1;
        let interval = self.report_interval.max(1);
        if !self.self_report && counter % interval == 0 {
            let total = succeed_count + fail_count;
            let percent = if total > 0 {
                // succeed/total clamped to 0..=100 (best-effort, no total-rows known).
                ((succeed_count.max(0) as f64 / total as f64) * 100.0).round() as u8
            } else {
                0
            };
            let msg = format!("succeed={succeed_count} fail={fail_count}");
            self.progress.report_progress(percent.min(100), &msg);
        }

        // 3. Cooperative cancel: stop at this committed safe-point.
        if self.is_cancelled() {
            return Err(SdkTaskStopped::at(break_position));
        }
        Ok(())
    }
}

/// Error from [`TaskContext::try_commit`]: either the checkpoint store failed to
/// persist, or the task was cooperatively cancelled at the safe-point.
#[derive(Debug)]
pub enum CommitError {
    /// The checkpoint `save` failed — the batch is not durably checkpointed.
    Checkpoint(crate::client::checkpoint::CheckpointError),
    /// Cooperative cancel observed at the committed safe-point.
    Stopped(SdkTaskStopped),
}

impl std::fmt::Display for CommitError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CommitError::Checkpoint(e) => write!(f, "{e}"),
            CommitError::Stopped(s) => write!(f, "{s}"),
        }
    }
}

impl std::error::Error for CommitError {}

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

/// ADR-037 决策三 — the resumable/cancellable SPI variant.
///
/// A long-running, checkpoint-driven handler returns `Result<TaskResult,
/// SdkTaskStopped>`: on the happy path a normal [`TaskResult`]; on cooperative
/// cancel it **propagates** the [`SdkTaskStopped`] raised by
/// [`TaskContext::commit`] up rather than swallowing it (吞了就停不下来). The
/// runtime / testkit maps a returned `Err(SdkTaskStopped)` to a **cancelled**
/// terminal report (via [`map_stopped_result`]), *not* a failure — the data is
/// consistent at the committed safe-point.
pub trait StoppableTaskHandler: Send {
    /// The task type this handler serves.
    fn task_type(&self) -> &str;

    /// Execute the task; return `Err(SdkTaskStopped)` to stop at a safe-point.
    fn execute(&self, ctx: &TaskContext) -> Result<TaskResult, SdkTaskStopped>;
}

/// Map a [`StoppableTaskHandler`] outcome to a terminal [`TaskResult`].
///
/// `Ok(result)` passes through unchanged; `Err(SdkTaskStopped)` becomes a
/// [`TaskResult::cancelled`] (wire `errorCode = CANCELLED`) — the run path
/// reports it as a cancelled terminal, not a failure. This is the single point
/// where ADR-037 决策三's "SdkTaskStopped → cancelled terminal" contract is
/// honored, so the run path and testkit share one mapping.
pub fn map_stopped_result(outcome: Result<TaskResult, SdkTaskStopped>) -> TaskResult {
    match outcome {
        Ok(result) => result,
        Err(stopped) => TaskResult::cancelled(&format!(
            "stopped at safe-point ({} break-position key(s))",
            stopped.break_position.len()
        )),
    }
}

/// A [`ProgressReporter`] that records every `report_progress` call. Useful for
/// asserting the ADR-037 commit rate-limiting (counter % `report_interval`).
#[derive(Debug, Default, Clone)]
pub struct RecordingProgressReporter {
    calls: Arc<std::sync::Mutex<Vec<(u8, String)>>>,
}

impl RecordingProgressReporter {
    /// A fresh recorder.
    pub fn new() -> Self {
        Self::default()
    }

    /// The recorded `(percent, message)` calls in order.
    pub fn calls(&self) -> Vec<(u8, String)> {
        self.calls
            .lock()
            .map(|g| g.clone())
            .unwrap_or_default()
    }

    /// How many times `report_progress` was called.
    pub fn count(&self) -> usize {
        self.calls.lock().map(|g| g.len()).unwrap_or(0)
    }
}

impl ProgressReporter for RecordingProgressReporter {
    fn report_progress(&self, percent: u8, message: &str) {
        if let Ok(mut g) = self.calls.lock() {
            g.push((percent, message.to_string()));
        }
    }
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
        let ctx = TaskContext::new("t1", "tenant-a", "demo");
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

    // ----- ADR-037 P1–P3 primitives -----

    use crate::client::checkpoint::{InMemoryCheckpoint, JsonValue, SdkCheckpointState};

    fn bp(key: &str, val: i64) -> BreakPosition {
        let mut m = BreakPosition::new();
        m.insert(key.to_string(), JsonValue::Int(val));
        m
    }

    #[test]
    fn checkpoint_accessor_loads_and_resumes() {
        // P1: a pre-seeded checkpoint is loadable from the context accessor and
        // carries the resume coordinates + restored counters.
        let store = Arc::new(InMemoryCheckpoint::new());
        store
            .save(
                "t1",
                &SdkCheckpointState {
                    break_position: bp("id", 500),
                    succeed_count: 500,
                    fail_count: 3,
                    completed: false,
                },
            )
            .expect("seed");

        let ctx = TaskContext::new("t1", "tenant-a", "import").with_checkpoint(store);
        let loaded = ctx.checkpoint().load("t1").expect("load").expect("present");
        assert_eq!(loaded.succeed_count, 500);
        assert_eq!(loaded.fail_count, 3);
        assert_eq!(loaded.break_position.get("id").and_then(JsonValue::as_i64), Some(500));
        assert!(!loaded.completed);
    }

    #[test]
    fn resume_skips_completed_task() {
        // P1: an already-completed checkpoint short-circuits (idempotent resume).
        let store = Arc::new(InMemoryCheckpoint::new());
        store
            .save(
                "done-task",
                &SdkCheckpointState { completed: true, succeed_count: 1000, ..Default::default() },
            )
            .expect("seed");
        let ctx = TaskContext::new("done-task", "tenant-a", "import").with_checkpoint(store);

        // The resume preamble a handler runs: load → if completed, skip.
        let state = ctx.checkpoint().load("done-task").expect("load").expect("present");
        let result = if state.completed {
            TaskResult::success("already completed; skipped")
        } else {
            TaskResult::failure("EXECUTION_FAILED", "would have re-run from scratch")
        };
        assert!(result.is_success());
        assert_eq!(result.result_summary, "already completed; skipped");
    }

    #[test]
    fn commit_saves_checkpoint_every_call() {
        // P2: each commit persists the latest break-position + counters.
        let store = Arc::new(InMemoryCheckpoint::new());
        let ctx = TaskContext::new("t1", "tenant-a", "import")
            .with_checkpoint(store.clone())
            .with_report_interval(100);

        ctx.commit(10, 0, bp("id", 10)).expect("commit");
        let s = store.load("t1").expect("load").expect("present");
        assert_eq!(s.succeed_count, 10);
        assert_eq!(s.break_position.get("id").and_then(JsonValue::as_i64), Some(10));
        assert!(!s.completed);
    }

    #[test]
    fn commit_reports_on_interval() {
        // P2: progress reports are rate-limited to every `report_interval` commits.
        let recorder = RecordingProgressReporter::new();
        let mut ctx = TaskContext::new("t1", "tenant-a", "import").with_report_interval(3);
        ctx.progress = Box::new(recorder.clone());

        for i in 1..=9i64 {
            ctx.commit(i, 0, bp("id", i)).expect("commit");
        }
        // 9 commits, interval 3 → reports at commit #3, #6, #9 = 3 reports.
        assert_eq!(recorder.count(), 3);
        // The message of the last report reflects the latest counters.
        let calls = recorder.calls();
        assert_eq!(calls.last().map(|(_, m)| m.as_str()), Some("succeed=9 fail=0"));
    }

    #[test]
    fn commit_self_report_suppresses_auto_progress() {
        // P2: self_report hands progress control back to the handler.
        let recorder = RecordingProgressReporter::new();
        let mut ctx = TaskContext::new("t1", "tenant-a", "import")
            .with_report_interval(1)
            .with_self_report(true);
        ctx.progress = Box::new(recorder.clone());

        for i in 1..=5i64 {
            ctx.commit(i, 0, bp("id", i)).expect("commit");
        }
        assert_eq!(recorder.count(), 0);
    }

    #[test]
    fn commit_returns_stopped_when_cancelled() {
        // P3: after a successful commit, an observed cancel stops at the safe-point.
        let ctx = TaskContext::new("t1", "tenant-a", "import").with_report_interval(1);
        // First commit succeeds (not yet cancelled).
        assert!(ctx.commit(10, 0, bp("id", 10)).is_ok());
        // Cancellation requested → next commit stops at the committed safe-point.
        ctx.cancellation.cancel();
        let err = ctx.commit(20, 0, bp("id", 20)).expect_err("should stop");
        assert_eq!(err.break_position.get("id").and_then(JsonValue::as_i64), Some(20));
    }

    #[test]
    fn try_commit_propagates_stop_and_persists() {
        // P2/P3: the fallible variant still persists then stops on cancel.
        let store = Arc::new(InMemoryCheckpoint::new());
        let ctx = TaskContext::new("t1", "tenant-a", "import").with_checkpoint(store.clone());
        ctx.cancellation.cancel();
        let err = ctx.try_commit(7, 1, bp("id", 7)).expect_err("stop");
        assert!(matches!(err, CommitError::Stopped(_)));
        // The batch was still durably checkpointed before the stop.
        let s = store.load("t1").expect("load").expect("present");
        assert_eq!(s.succeed_count, 7);
    }

    #[test]
    fn map_stopped_result_yields_cancelled_terminal() {
        // P3: SdkTaskStopped maps to a CANCELLED terminal, not a failure.
        let ok = map_stopped_result(Ok(TaskResult::success("done")));
        assert!(ok.is_success());

        let stopped = map_stopped_result(Err(SdkTaskStopped::at(bp("id", 42))));
        assert_eq!(stopped.error_code, "CANCELLED");
        assert!(!stopped.is_success());
    }
}
