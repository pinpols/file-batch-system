//! Phase-2 runtime integration tests — the SAME scenarios the TS/Go phase-2
//! suites cover, driven end-to-end through the std-only engine + fakes:
//!
//! * transport retry / fatal / idempotent classification via `FakeTransport`,
//! * scheduler DRAINING directive + dynamic interval (PT15S -> 15000),
//! * lifecycle stop ordering (drain -> wake -> drain-budget -> shutdown -> deactivate),
//! * sensitive validator catches credentials,
//! * tenant self-check drops foreign-tenant records,
//! * handler cancellation,
//! * consumer schemaVersion-reject + foreign-tenant-drop.

use batch_worker_sdk::client::consumer::{FakeConsumer, MessageOutcome, TaskRecord};
use batch_worker_sdk::client::handler::{TaskContext, TaskHandler, TaskResult};
use batch_worker_sdk::client::lifecycle::{StopStep, Worker, WorkerState};
use batch_worker_sdk::client::scheduler::{
    HeartbeatScheduler, LeaseRenewalScheduler, DEFAULT_HEARTBEAT_INTERVAL_MS,
};
use batch_worker_sdk::client::sensitive::SensitiveValidator;
use batch_worker_sdk::client::testkit::{run_happy_path, FakePlatform};
use batch_worker_sdk::client::transport::{
    classify_response, FakeTransport, HttpResponse, Transport, TransportOutcome,
};
use batch_worker_sdk::protocol::{HeartbeatHint, HeartbeatResponse, RenewResponse};

// ---------------------------------------------------------------------------
// Transport: retry / fatal / idempotent
// ---------------------------------------------------------------------------

#[test]
fn transport_retry_fatal_idempotent() {
    let t = FakeTransport::new();
    t.queue_report(HttpResponse::new(503, "")); // retry
    t.queue_report(HttpResponse::new(403, "")); // fatal
    t.queue_report(HttpResponse::new(409, "")); // idempotent

    let r1 = t.report("task", "{}");
    assert!(matches!(
        classify_response(&r1, 0),
        TransportOutcome::Retry { .. }
    ));

    let r2 = t.report("task", "{}");
    assert_eq!(classify_response(&r2, 0), TransportOutcome::FailFast);

    let r3 = t.report("task", "{}");
    assert_eq!(classify_response(&r3, 0), TransportOutcome::IdempotentSuccess);
}

// ---------------------------------------------------------------------------
// Scheduler: DRAINING directive + dynamic interval
// ---------------------------------------------------------------------------

#[test]
fn scheduler_applies_draining_and_dynamic_interval() {
    let mut hb = HeartbeatScheduler::new("w1", FakeTransport::new());
    assert_eq!(hb.interval_ms, DEFAULT_HEARTBEAT_INTERVAL_MS);

    let resp = HeartbeatResponse {
        platform_status: Some("DRAINING".to_string()),
        next_heartbeat_hint: Some(HeartbeatHint::Iso("PT15S".to_string())),
        ..Default::default()
    };
    let tick = hb.apply(200, &resp);
    let d = tick.decision.expect("2xx directive");
    assert_eq!(d.fsm_transition.as_deref(), Some("DRAINING"));
    assert_eq!(d.kafka.as_deref(), Some("pause"));
    assert_eq!(tick.next_interval_ms, 15_000);
    assert_eq!(hb.interval_ms, 15_000);
}

#[test]
fn lease_renew_cancel_and_drop() {
    let lease = LeaseRenewalScheduler::new(FakeTransport::new());

    let cancel = lease.renew_one(
        "task-1",
        200,
        &RenewResponse {
            cancel_requested: Some(true),
            ..Default::default()
        },
    );
    assert_eq!(cancel.decision.unwrap().action, "cancel");
    assert!(!cancel.drop_local);

    let dropped = lease.renew_one("task-2", 404, &RenewResponse::default());
    assert!(dropped.drop_local);
}

// ---------------------------------------------------------------------------
// Lifecycle: stop ordering
// ---------------------------------------------------------------------------

#[test]
fn lifecycle_stop_ordering() {
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
    assert_eq!(w.state(), WorkerState::Draining);
}

// ---------------------------------------------------------------------------
// Sensitive: catches credentials
// ---------------------------------------------------------------------------

#[test]
fn sensitive_catches_credentials() {
    let v = SensitiveValidator::new();
    let leaked = [("apiKey", "AKIA-real-value"), ("region", "eu")];
    assert!(v.validate(leaked).is_rejected());

    let safe = [("apiKey", ""), ("region", "eu")];
    assert!(v.validate(safe).is_ok());
}

// ---------------------------------------------------------------------------
// Tenant self-check: drops foreign
// ---------------------------------------------------------------------------

#[test]
fn tenant_check_drops_foreign() {
    let c = FakeConsumer::new("tenant-a", 4);
    let foreign = TaskRecord::new("t", "tenant-evil", "import", Some("v1"));
    let out = c.handle(&foreign, 0);
    assert_eq!(out, MessageOutcome::DropForeignTenant);
    assert!(!out.should_commit_offset());
}

// ---------------------------------------------------------------------------
// Handler cancellation
// ---------------------------------------------------------------------------

struct LoopHandler;
impl TaskHandler for LoopHandler {
    fn task_type(&self) -> &str {
        "loop"
    }
    fn execute(&self, ctx: &TaskContext) -> TaskResult {
        for _ in 0..1_000_000 {
            if ctx.is_cancelled() {
                return TaskResult::cancelled("cancelled mid-flight");
            }
        }
        TaskResult::success("done")
    }
}

#[test]
fn handler_cancellation() {
    let mut ctx = TaskContext::new("t1", "tenant-a", "loop");
    ctx.cancellation.cancel();
    let result = LoopHandler.execute(&ctx);
    assert_eq!(result.error_code, "CANCELLED");
}

// ---------------------------------------------------------------------------
// Consumer: schemaVersion reject + foreign-tenant drop
// ---------------------------------------------------------------------------

#[test]
fn consumer_schema_reject_and_foreign_drop() {
    let c = FakeConsumer::new("tenant-a", 4);

    let bad_schema = TaskRecord::new("t", "tenant-a", "import", Some("v3"));
    assert_eq!(c.handle(&bad_schema, 0), MessageOutcome::RejectSchema);

    let foreign = TaskRecord::new("t", "tenant-b", "import", Some("v1"));
    assert_eq!(c.handle(&foreign, 0), MessageOutcome::DropForeignTenant);

    let ok = TaskRecord::new("t", "tenant-a", "import", Some("v2"));
    assert_eq!(c.handle(&ok, 0), MessageOutcome::Accept { paused: false });
}

// ---------------------------------------------------------------------------
// End-to-end happy path via FakePlatform
// ---------------------------------------------------------------------------

struct EchoHandler;
impl TaskHandler for EchoHandler {
    fn task_type(&self) -> &str {
        "echo"
    }
    fn execute(&self, _ctx: &TaskContext) -> TaskResult {
        TaskResult::success("ok")
    }
}

#[test]
fn end_to_end_happy_path() {
    let platform = FakePlatform::new("tenant-a", 4);
    let record = TaskRecord::new("t1", "tenant-a", "echo", Some("v1"));
    let (state, result, report) = run_happy_path(&platform, "w1", &EchoHandler, &record);

    assert_eq!(state, WorkerState::Draining);
    assert!(result.unwrap().is_success());
    assert_eq!(report.deactivate_outcome, TransportOutcome::Success);
    // Full lease cycle: register -> claim -> report -> deactivate (the worker
    // must CLAIM before executing the handler, §1.1).
    assert_eq!(
        platform.call_log(),
        vec![
            "register".to_string(),
            "claim".to_string(),
            "report".to_string(),
            "deactivate".to_string()
        ]
    );
}
