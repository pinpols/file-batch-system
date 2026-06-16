//! Test harness — [`FakePlatform`] wires a [`FakeTransport`] together with a
//! [`FakeConsumer`] so an end-to-end worker scenario can be driven without any
//! real HTTP/Kafka. Mirrors the Java SDK testkit and the TS/Go phase-2 testkit.
//!
//! This is the one-stop fixture a tenant uses to assert their handler + config
//! against the protocol engine (register → heartbeat directive → consume →
//! execute → report → stop) entirely in-process.

use crate::client::consumer::{FakeConsumer, MessageOutcome, TaskRecord};
use crate::client::handler::{TaskContext, TaskHandler, TaskResult};
use crate::client::lifecycle::{StopReport, Worker, WorkerState};
use crate::client::transport::{FakeTransport, HttpResponse, Transport};

/// An in-memory platform + worker rig for end-to-end SDK tests.
pub struct FakePlatform {
    pub transport: FakeTransport,
    pub consumer: FakeConsumer,
    pub config_tenant_id: String,
    pub max_concurrent: i64,
}

impl FakePlatform {
    /// New rig bound to a tenant id and concurrency cap.
    pub fn new(config_tenant_id: &str, max_concurrent: i64) -> Self {
        Self {
            transport: FakeTransport::new(),
            consumer: FakeConsumer::new(config_tenant_id, max_concurrent),
            config_tenant_id: config_tenant_id.to_string(),
            max_concurrent,
        }
    }

    /// A `Worker` sharing this rig's transport.
    pub fn worker(&self, worker_code: &str) -> Worker<FakeTransport> {
        Worker::new(worker_code, self.transport.clone())
    }

    /// Queue a register response (default 200 if never called).
    pub fn given_register(&self, status: i64) -> &Self {
        self.transport.queue_register(HttpResponse::new(status, "{}"));
        self
    }

    /// Queue a claim response (default 200).
    pub fn given_claim(&self, status: i64) -> &Self {
        self.transport.queue_claim(HttpResponse::new(status, "{}"));
        self
    }

    /// Queue a report response (default 200).
    pub fn given_report(&self, status: i64) -> &Self {
        self.transport.queue_report(HttpResponse::new(status, "{}"));
        self
    }

    /// Queue a deactivate response (default 200).
    pub fn given_deactivate(&self, status: i64) -> &Self {
        self.transport.queue_deactivate(HttpResponse::new(status, ""));
        self
    }

    /// Run the consumer pipeline for one record at a given in-flight count.
    pub fn consume(&self, record: &TaskRecord, in_flight: i64) -> MessageOutcome {
        self.consumer.handle(record, in_flight)
    }

    /// Drive a full happy-path: consume an accepted record, execute the handler,
    /// and report. Returns the handler's [`TaskResult`]. Records the report call
    /// on the transport so ordering can be asserted.
    pub fn run_task<H: TaskHandler>(
        &self,
        handler: &H,
        record: &TaskRecord,
        in_flight: i64,
    ) -> Option<TaskResult> {
        match self.consume(record, in_flight) {
            MessageOutcome::Accept { .. } => {
                // CLAIM before execute (§1.1): the worker must take the lease
                // before running the handler. The claim body carries the
                // partitionInvocationId threaded from the dispatch record.
                let claim_body = record
                    .partition_invocation_id
                    .clone()
                    .unwrap_or_default();
                let _ = self.transport.claim(&record.task_id, &claim_body);
                let ctx = TaskContext::new(&record.task_id, &record.tenant_id, &record.task_type)
                    .with_partition_invocation_id(record.partition_invocation_id.clone());
                let result = handler.execute(&ctx);
                // Report terminal status to the platform.
                let _ = self.transport.report(&record.task_id, &result.error_code);
                Some(result)
            }
            // Rejected / dropped records never reach the handler.
            _ => None,
        }
    }

    /// The transport's ordered call log.
    pub fn call_log(&self) -> Vec<String> {
        self.transport.call_log()
    }
}

/// Convenience: full register → consume → run → stop walkthrough used by the
/// runtime integration test (kept here so the assertions live with the rig).
pub fn run_happy_path<H: TaskHandler>(
    platform: &FakePlatform,
    worker_code: &str,
    handler: &H,
    record: &TaskRecord,
) -> (WorkerState, Option<TaskResult>, StopReport) {
    platform.given_register(200);
    platform.given_claim(200);
    platform.given_report(200);
    platform.given_deactivate(200);

    let mut worker = platform.worker(worker_code);
    worker.start("{}", false).expect("register");
    let result = platform.run_task(handler, record, 0);
    let report = worker.stop(30_000);
    (worker.state(), result, report)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::client::handler::TaskContext;

    struct EchoHandler;
    impl TaskHandler for EchoHandler {
        fn task_type(&self) -> &str {
            "echo"
        }
        fn execute(&self, _ctx: &TaskContext) -> TaskResult {
            TaskResult::success("echoed")
        }
    }

    #[test]
    fn fake_platform_end_to_end_happy_path() {
        let platform = FakePlatform::new("tenant-a", 4);
        let record = TaskRecord::new("t1", "tenant-a", "echo", Some("v1"));
        let (state, result, report) =
            run_happy_path(&platform, "w1", &EchoHandler, &record);

        assert_eq!(state, WorkerState::Draining);
        assert_eq!(result.unwrap().result_summary, "echoed");
        // register -> claim -> report -> deactivate in order (full lease cycle).
        assert_eq!(
            platform.call_log(),
            vec![
                "register".to_string(),
                "claim".to_string(),
                "report".to_string(),
                "deactivate".to_string()
            ]
        );
        assert_eq!(report.deactivate_outcome, crate::client::transport::TransportOutcome::Success);
    }

    #[test]
    fn foreign_tenant_record_never_runs_handler() {
        let platform = FakePlatform::new("tenant-a", 4);
        let foreign = TaskRecord::new("t1", "tenant-b", "echo", Some("v1"));
        let result = platform.run_task(&EchoHandler, &foreign, 0);
        assert!(result.is_none());
        // No report call — record was dropped pre-handler.
        assert!(platform.call_log().is_empty());
    }
}
