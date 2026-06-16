//! Kafka consumer engine (§1.2) — the `on_message` pipeline, std-only and
//! synchronous. No real Kafka: the [`Consumer`] trait is the seam a real
//! `kafkajs`/`rdkafka`/`segmentio` adapter implements; [`FakeConsumer`] drives
//! the pipeline in tests.
//!
//! Mirrors the Java SDK `KafkaTaskConsumer` and the TS/Go phase-2 consumer.
//!
//! `on_message(record)` pipeline, in order:
//! 1. parse the dispatch record (here: a typed [`TaskRecord`]; the real adapter
//!    does JSON-with-ignore-unknown per §1.2),
//! 2. [`classify_schema_version`] — unknown major (`v3+`) → **reject**, do not
//!    commit offset,
//! 3. **tenant self-check (§1.9)** — `record.tenant_id != config.tenant_id` →
//!    drop + log error, do not commit offset (last-line ACL defense),
//! 4. [`decide_backpressure`] — if in-flight has reached capacity, pause the
//!    assignment and resume once a slot drains.

use crate::decide::{classify_schema_version, decide_backpressure, ACCEPT};

/// A parsed dispatch record (the fields the engine needs to decide; the real
/// adapter ignores any unknown JSON fields per §1.2).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TaskRecord {
    pub task_id: String,
    pub tenant_id: String,
    pub task_type: String,
    /// `schemaVersion` (`None` / empty → treated as v1).
    pub schema_version: Option<String>,
    /// `partitionInvocationId` — the per-partition invocation token the real
    /// adapter threads into claim/renew/report bodies (ADR-014, fixture 10).
    /// `None` when the dispatch message omits it.
    pub partition_invocation_id: Option<String>,
}

impl TaskRecord {
    pub fn new(task_id: &str, tenant_id: &str, task_type: &str, schema_version: Option<&str>) -> Self {
        Self {
            task_id: task_id.to_string(),
            tenant_id: tenant_id.to_string(),
            task_type: task_type.to_string(),
            schema_version: schema_version.map(|s| s.to_string()),
            partition_invocation_id: None,
        }
    }

    /// Builder-style: attach the `partitionInvocationId` carried by the dispatch
    /// message so it can flow into claim/renew/report.
    pub fn with_partition_invocation_id(mut self, id: Option<String>) -> Self {
        self.partition_invocation_id = id;
        self
    }
}

/// The disposition of a record after the `on_message` pipeline.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MessageOutcome {
    /// Accept and dispatch to a handler; `paused` signals capacity backpressure
    /// was applied (assignment paused; resume once a slot drains).
    Accept { paused: bool },
    /// Unknown schemaVersion major (§A) — reject, do NOT commit offset.
    RejectSchema,
    /// Foreign tenant (§1.9) — drop + log error, do NOT commit offset.
    DropForeignTenant,
}

impl MessageOutcome {
    /// Whether the consumer may commit the offset for this record.
    /// Rejected/dropped records must NOT be committed (§1.2 / §1.9).
    pub fn should_commit_offset(&self) -> bool {
        matches!(self, MessageOutcome::Accept { .. })
    }
}

/// The consumer seam. A real adapter calls [`Consumer::on_message`] for each
/// polled record and then `pause`/`resume`/`commit` per the returned outcome.
pub trait Consumer {
    /// Run the §1.2 pipeline for one record given current in-flight count and
    /// the worker's own tenant id (config).
    fn on_message(
        &self,
        record: &TaskRecord,
        in_flight: i64,
        max_concurrent: i64,
        config_tenant_id: &str,
    ) -> MessageOutcome;
}

/// The shared `on_message` implementation (used by both the fake and any real
/// adapter that delegates here).
pub fn run_pipeline(
    record: &TaskRecord,
    in_flight: i64,
    max_concurrent: i64,
    config_tenant_id: &str,
) -> MessageOutcome {
    // 2. schemaVersion compatibility (§A).
    if classify_schema_version(record.schema_version.as_deref()) != ACCEPT {
        return MessageOutcome::RejectSchema;
    }
    // 3. tenant self-check (§1.9) — last-line ACL defense.
    if record.tenant_id != config_tenant_id {
        return MessageOutcome::DropForeignTenant;
    }
    // 4. capacity-aware backpressure.
    let bp = decide_backpressure(in_flight, max_concurrent);
    MessageOutcome::Accept {
        paused: bp.action == "backpressure",
    }
}

/// In-memory consumer for tests; delegates to [`run_pipeline`].
#[derive(Debug, Default, Clone)]
pub struct FakeConsumer {
    pub config_tenant_id: String,
    pub max_concurrent: i64,
}

impl FakeConsumer {
    pub fn new(config_tenant_id: &str, max_concurrent: i64) -> Self {
        Self {
            config_tenant_id: config_tenant_id.to_string(),
            max_concurrent,
        }
    }

    /// Convenience wrapper binding this fake's tenant + capacity.
    pub fn handle(&self, record: &TaskRecord, in_flight: i64) -> MessageOutcome {
        self.on_message(record, in_flight, self.max_concurrent, &self.config_tenant_id)
    }
}

impl Consumer for FakeConsumer {
    fn on_message(
        &self,
        record: &TaskRecord,
        in_flight: i64,
        max_concurrent: i64,
        config_tenant_id: &str,
    ) -> MessageOutcome {
        run_pipeline(record, in_flight, max_concurrent, config_tenant_id)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_well_formed_own_tenant_message() {
        let c = FakeConsumer::new("tenant-a", 4);
        let rec = TaskRecord::new("t1", "tenant-a", "import", Some("v1"));
        assert_eq!(c.handle(&rec, 0), MessageOutcome::Accept { paused: false });
    }

    #[test]
    fn rejects_unknown_schema_version() {
        let c = FakeConsumer::new("tenant-a", 4);
        let rec = TaskRecord::new("t1", "tenant-a", "import", Some("v3"));
        let out = c.handle(&rec, 0);
        assert_eq!(out, MessageOutcome::RejectSchema);
        assert!(!out.should_commit_offset(), "must not commit on reject");
    }

    #[test]
    fn drops_foreign_tenant_message() {
        let c = FakeConsumer::new("tenant-a", 4);
        let rec = TaskRecord::new("t1", "tenant-b", "import", Some("v1"));
        let out = c.handle(&rec, 0);
        assert_eq!(out, MessageOutcome::DropForeignTenant);
        assert!(!out.should_commit_offset(), "must not commit on foreign drop");
    }

    #[test]
    fn applies_backpressure_at_capacity() {
        let c = FakeConsumer::new("tenant-a", 4);
        let rec = TaskRecord::new("t1", "tenant-a", "import", Some("v1"));
        // in_flight == max -> accept but paused.
        assert_eq!(c.handle(&rec, 4), MessageOutcome::Accept { paused: true });
    }

    #[test]
    fn null_and_empty_schema_treated_as_v1() {
        let c = FakeConsumer::new("tenant-a", 4);
        for sv in [None, Some("")] {
            let rec = TaskRecord::new("t1", "tenant-a", "import", sv);
            assert_eq!(c.handle(&rec, 0), MessageOutcome::Accept { paused: false });
        }
    }

    #[test]
    fn schema_check_precedes_tenant_check() {
        // A foreign-tenant message with a bad schema rejects on schema first
        // (matches the pipeline order in run_pipeline / TS/Go).
        let c = FakeConsumer::new("tenant-a", 4);
        let rec = TaskRecord::new("t1", "tenant-b", "import", Some("v9"));
        assert_eq!(c.handle(&rec, 0), MessageOutcome::RejectSchema);
    }
}
