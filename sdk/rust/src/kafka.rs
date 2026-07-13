//! Phase 3 — the **real Kafka consumer adapter** (`#[cfg(feature = "kafka")]`).
//!
//! This is the broker-bound layer that drives the phase-2 `on_message` pipeline
//! ([`crate::client::consumer::run_pipeline`]) with messages polled from a real
//! Kafka topic via [`rdkafka`]. It mirrors, field-for-field, the other reference
//! adapters:
//!
//! * Java   — `io.github.pinpols.batch.sdk.dispatcher.KafkaTaskConsumer` (Apache Kafka client),
//! * Python — `batch_worker_sdk.internal._kafka.KafkaTaskConsumer` (aiokafka),
//! * (the TS/Go phase-2 `consumer.*` files carry the seam + `FakeConsumer`).
//!
//! ## Why `BaseConsumer` (sync) and not `StreamConsumer` (async)
//!
//! The phase-1/2 engine is **std-only and synchronous** (per-cycle `tick()`,
//! `Arc<AtomicBool>` cancel flags — no tokio). `StreamConsumer` would drag in a
//! `tokio` runtime; `BaseConsumer` is a blocking single-threaded poll loop that
//! matches the engine's model exactly and keeps the `kafka` feature's dependency
//! surface to just `rdkafka`. The poll loop here is the direct analog of the
//! Java `run()` loop and the Python `_poll_loop`.
//!
//! ## Pipeline (byo-sdk-guide §1.2 / §1.9)
//!
//! For each polled record:
//! 1. UTF-8 + JSON deserialize, ignoring unknown fields (§1.2),
//! 2. [`run_pipeline`] runs schemaVersion-reject (§A) → tenant self-check (§1.9)
//!    → capacity backpressure (§1.5),
//! 3. on **accept** → dispatch to the handler, then **commit** the offset.
//!
//! ## Offset disposition — the load-bearing part (§1.2, fixture 18/28/30)
//!
//! The committed offset is only allowed to move on *real* progress, and a
//! withheld record must **stay withheld** — a `BaseConsumer`'s in-memory position
//! advances every `poll()` even without a commit, so simply "not committing" is a
//! bug: a later record's commit would silently jump the committed offset **past**
//! the withheld one. Each disposition therefore maps to an explicit
//! [`OffsetAction`] the poll loop applies:
//!
//! * **Advance** (`Accepted`, `DecodeError`, `HandlerPanicked`) — commit
//!   `offset+1`. `Accepted` is real progress; `DecodeError`/`HandlerPanicked` are
//!   **commit-skip** (fixture 30): an undecodable / deterministically-panicking
//!   record is unrecoverable poison, so we advance past it rather than let one
//!   corrupt message head-of-line block the partition forever.
//! * **Withhold** (`RejectedSchema` §A, `DroppedForeignTenant` §1.9) — the
//!   record is valid-but-unconsumable-now (an unknown schema major / a foreign
//!   tenant); re-delivery to a *fixed* deployment is meaningful. We record the
//!   record's own offset as that partition's **commit ceiling** (the LOWEST
//!   withheld offset) and **keep consuming** — subsequent records still flow, so
//!   one withheld record never head-of-line blocks the partition. No later commit
//!   may cross the ceiling, so the withheld offset is never committed and is
//!   redelivered on the next rebalance / restart. This mirrors the Go SDK's
//!   `committable` ceiling exactly; we do NOT `seek` back and do NOT `pause`.
//! * **RewindRetry** (`Backpressure` §1.5, `HandlerRetryLater`) — a valid record
//!   deferred by capacity or a transient handler error; `seek` back so it is
//!   re-read on the next cycle / after capacity resume, without a per-partition
//!   pause block.
//!
//! All withhold / drop / poison / panic events are logged (`log` facade) — never
//! silently swallowed.
//!
//! ## Security (§1.8)
//!
//! SASL/SCRAM-SHA-512 + SSL credentials come from [`KafkaConsumerConfig`]
//! (populated from env / secret by the caller), **never** from the message
//! payload. All three SASL fields blank → PLAINTEXT (local dev); any set → the
//! whole SASL block is forwarded to the client, matching the Java/Python adapters.

#![cfg(feature = "kafka")]

use std::collections::HashMap;
use std::panic::AssertUnwindSafe;
use std::time::Duration;

use rdkafka::config::ClientConfig;
use rdkafka::consumer::{BaseConsumer, CommitMode, Consumer as RdConsumer};
use rdkafka::error::KafkaError;
use rdkafka::message::Message;
use rdkafka::{Offset, TopicPartitionList};

use crate::client::consumer::{run_pipeline, MessageOutcome, TaskRecord};

/// Topic-subscription / consumer-group / SASL configuration for the adapter.
///
/// Mirrors the Kafka-related fields of the Java `BatchPlatformClientConfig` and
/// the Python config. Credentials are supplied by the caller (env / secret) and
/// never read from a message payload (§1.8).
///
/// `Debug` is hand-written (not derived) so a `{:?}` — in a log line, a panic
/// message, an error chain — never leaks the SASL password. See the `impl` below.
#[derive(Clone)]
pub struct KafkaConsumerConfig {
    /// Kafka bootstrap servers, e.g. `"broker-1:9092,broker-2:9092"`.
    pub bootstrap_servers: String,
    /// The worker's own tenant id — the §1.9 self-check compares every message
    /// against this.
    pub tenant_id: String,
    /// The worker code — combined with `tenant_id` into the consumer group id.
    pub worker_code: String,
    /// Max in-flight tasks; backpressure pauses the assignment at this cap.
    pub max_concurrent: i64,
    /// `client.config` poll timeout per `poll()` call (Java `kafkaPollInterval`).
    pub poll_interval: Duration,

    // ── SASL/SSL (§1.8). All three blank → PLAINTEXT. ──────────────────────
    /// `security.protocol`, e.g. `"SASL_SSL"`. Blank → omitted (PLAINTEXT).
    pub security_protocol: Option<String>,
    /// `sasl.mechanism`, defaults to `SCRAM-SHA-512` when a username is set and
    /// this is left blank.
    pub sasl_mechanism: Option<String>,
    /// `sasl.username`.
    pub sasl_username: Option<String>,
    /// `sasl.password`.
    pub sasl_password: Option<String>,
}

impl std::fmt::Debug for KafkaConsumerConfig {
    /// Redacts `sasl_password` so `{:?}` (logs / panics / error chains) can never
    /// leak the secret. `sasl_username` is an identity, not a secret, so it is
    /// shown; the password is rendered as a fixed `***` marker only when present.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("KafkaConsumerConfig")
            .field("bootstrap_servers", &self.bootstrap_servers)
            .field("tenant_id", &self.tenant_id)
            .field("worker_code", &self.worker_code)
            .field("max_concurrent", &self.max_concurrent)
            .field("poll_interval", &self.poll_interval)
            .field("security_protocol", &self.security_protocol)
            .field("sasl_mechanism", &self.sasl_mechanism)
            .field("sasl_username", &self.sasl_username)
            .field("sasl_password", &self.sasl_password.as_ref().map(|_| "***"))
            .finish()
    }
}

impl KafkaConsumerConfig {
    /// The node-direct topic subscription (§1.2), aligned with the built-in
    /// workers' `AbstractTaskConsumer.topicPattern()`:
    /// `batch.task.dispatch.<workerType>.node.<workerCode>` (base-first),
    /// expressed as an rdkafka/librdkafka regex (a leading `^` makes librdkafka
    /// treat the pattern as a regex).
    ///
    /// The OLD tenant-first `batch.task.dispatch.<tenant>.*` is never published
    /// to by the platform (#2) → a worker subscribing there receives nothing.
    /// Cross-tenant safety is enforced by the per-message tenant self-check
    /// (§1.9), not the topic name. `.` is escaped to match a literal dot; the
    /// `.*` matches the `<workerType>` segment (import/export/process/dispatch/
    /// atomic) so this one subscription is workerType-agnostic.
    pub fn topic_regex(&self) -> String {
        format!(
            "^batch\\.task\\.dispatch\\..*\\.node\\.{}",
            regex_escape(&self.worker_code)
        )
    }

    /// The per-worker consumer group id (§1.2): `g-sdk-<tenant>-<worker>`.
    pub fn group_id(&self) -> String {
        format!("g-sdk-{}-{}", self.tenant_id, self.worker_code)
    }

    /// Build the librdkafka [`ClientConfig`]. PLAINTEXT when no SASL username is
    /// configured; otherwise SASL/SCRAM (default mechanism `SCRAM-SHA-512`).
    pub fn to_client_config(&self) -> ClientConfig {
        let mut cfg = ClientConfig::new();
        cfg.set("bootstrap.servers", &self.bootstrap_servers)
            .set("group.id", self.group_id())
            // Manual offset commit — we only advance on pipeline accept (§1.2).
            .set("enable.auto.commit", "false")
            .set("auto.offset.reset", "latest");

        if let Some(proto) = non_blank(&self.security_protocol) {
            cfg.set("security.protocol", proto);
        }
        // SCRAM-SHA-512 is the guide default (§1.2); fall back to it when a
        // username is present but the mechanism was left blank.
        if let Some(user) = non_blank(&self.sasl_username) {
            let mech = non_blank(&self.sasl_mechanism).unwrap_or("SCRAM-SHA-512");
            cfg.set("sasl.mechanism", mech);
            cfg.set("sasl.username", user);
            if let Some(pass) = non_blank(&self.sasl_password) {
                cfg.set("sasl.password", pass);
            }
        }
        cfg
    }
}

/// Returns `Some(trimmed)` when the option holds a non-blank string, else `None`.
fn non_blank(s: &Option<String>) -> Option<&str> {
    s.as_deref().map(str::trim).filter(|s| !s.is_empty())
}

/// Best-effort extraction of a human-readable message from a caught panic payload
/// (`catch_unwind`'s `Box<dyn Any>`). Rust panics carry a `&str` or `String`
/// payload; anything else is reported as an opaque marker.
fn panic_message(payload: &(dyn std::any::Any + Send)) -> String {
    if let Some(s) = payload.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "<non-string panic payload>".to_string()
    }
}

/// Escape librdkafka-regex metacharacters in a tenant id so it is matched
/// literally inside [`KafkaConsumerConfig::topic_regex`]. Tenant ids are
/// normally `[a-z0-9-]`, but escaping keeps the subscription safe if that ever
/// drifts.
fn regex_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        if matches!(
            c,
            '.' | '\\' | '+' | '*' | '?' | '(' | ')' | '|' | '[' | ']' | '{' | '}' | '^' | '$'
        ) {
            out.push('\\');
        }
        out.push(c);
    }
    out
}

/// A handler invoked for each accepted, in-tenant, processable message. The
/// real worker wires this to the claim → execute → report lifecycle; the offset
/// is committed only after this returns (Java `commitSync` after dispatch).
pub trait MessageHandler {
    /// Process one accepted dispatch message. Returning `Err` withholds the
    /// offset commit (the record will be re-read on the next poll), matching the
    /// Java `RETRY_LATER` path.
    fn on_accepted(&mut self, msg: &DispatchMessage) -> Result<(), String>;
}

/// The decoded dispatch payload — only the fields the pipeline needs are typed;
/// unknown JSON fields are ignored (§1.2 `ignoreUnknown` equivalent). The full
/// `parameters` / `runtimeAttributes` maps are exposed as raw JSON for handlers.
#[derive(Debug, Clone, serde::Deserialize)]
pub struct DispatchMessage {
    /// `taskId` — the BE publishes this as a JSON **number** (`integer/int64`),
    /// so it must deserialize to `i64`; binding it to `String` makes serde fail
    /// on every real dispatch message (decode-error loop). Downstream string ids
    /// are produced with [`DispatchMessage::task_id_str`] / `.to_string()`.
    #[serde(rename = "taskId", default)]
    pub task_id: i64,
    #[serde(rename = "tenantId", default)]
    pub tenant_id: String,
    #[serde(rename = "schemaVersion", default)]
    pub schema_version: Option<String>,
    /// Bound to wire `workerType` (the consumer-side routing key). `taskType` was
    /// removed in the v2 envelope (it was redundant with `workerType`); the
    /// `alias` keeps v1 payloads decoding. Kept named `task_type` internally so
    /// the rest of the engine (which decides on "task type") is unchanged.
    #[serde(alias = "taskType", rename = "workerType", default)]
    pub task_type: String,
    /// `partitionInvocationId` — the per-partition invocation token threaded into
    /// claim/renew/report bodies (ADR-014, fixture 10). `nullable` on the wire.
    #[serde(rename = "partitionInvocationId", default)]
    pub partition_invocation_id: Option<String>,
    /// Remaining fields (parameters, runtimeAttributes, idempotencyKey, …) kept
    /// as raw JSON so handlers can read them without this struct having to
    /// enumerate the whole schema.
    #[serde(flatten)]
    pub extra: std::collections::BTreeMap<String, serde_json::Value>,
}

impl DispatchMessage {
    /// The task id as the string form the control-plane path / report body uses
    /// (`/internal/tasks/{id}/…`).
    pub fn task_id_str(&self) -> String {
        self.task_id.to_string()
    }

    /// Project to the engine's [`TaskRecord`] (the subset the pipeline decides
    /// on), carrying through the `partitionInvocationId` so the real adapter can
    /// include it in claim/renew/report.
    fn to_record(&self) -> TaskRecord {
        TaskRecord::new(
            &self.task_id_str(),
            &self.tenant_id,
            &self.task_type,
            self.schema_version.as_deref(),
        )
        .with_partition_invocation_id(self.partition_invocation_id.clone())
    }
}

/// The disposition of one polled record after the adapter has run it through the
/// pipeline (a superset of [`MessageOutcome`] that also names decode failures and
/// handler panics).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RecordDisposition {
    /// Accepted, dispatched, offset committed (real progress).
    Accepted,
    /// At capacity — assignment paused, offset withheld + rewound (re-read on
    /// resume).
    Backpressure,
    /// Unknown schemaVersion major — rejected; offset withheld (partition commit
    /// ceiling set, consumption continues) until a fixed deploy re-reads it (§A).
    RejectedSchema,
    /// Foreign tenant — dropped; offset withheld (partition commit ceiling set,
    /// consumption continues) (§1.9).
    DroppedForeignTenant,
    /// Empty or non-JSON payload — undecodable poison; **commit-skip** past it so
    /// one corrupt message cannot head-of-line block the partition (fixture 30).
    DecodeError,
    /// Handler returned `Err` — transient failure; offset withheld + rewound
    /// (retry on next poll).
    HandlerRetryLater,
    /// Handler **panicked** — caught so the poll loop survives; **commit-skip**
    /// past it (like poison) so a deterministically-panicking record cannot wedge
    /// the partition. The handler bridge is responsible for the fail REPORT.
    HandlerPanicked,
}

/// What the poll loop must do with the committed offset for a given disposition.
///
/// Making this explicit (rather than a single `committed()` bool) is the fix for
/// the offset-withhold bug: a `BaseConsumer`'s position advances on every poll, so
/// a withheld record must set a commit ceiling (a valid-but-unconsumable record)
/// or be actively rewound (a deferred record) — otherwise a later commit silently
/// skips past it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OffsetAction {
    /// Commit `offset+1` — real progress (`Accepted`) or commit-skip past poison
    /// (`DecodeError` / `HandlerPanicked`, fixture 30). Suppressed when this
    /// record's offset is at/above the partition's withheld ceiling (a lower
    /// record was withheld earlier), so no commit ever crosses a withheld offset.
    Advance,
    /// Withhold: rewind the position to this record so it is re-read next cycle /
    /// after capacity resume. Committed offset is NOT advanced.
    RewindRetry,
    /// Withhold via commit ceiling: record this (topic, partition)'s LOWEST
    /// withheld offset and **keep consuming** subsequent records (no seek, no
    /// pause). The committed offset is never advanced across the ceiling, so the
    /// withheld record is redelivered on the next rebalance / restart while later
    /// records still flow — no head-of-line block. Mirrors the Go SDK's
    /// `committable` ceiling.
    Withhold,
}

impl RecordDisposition {
    /// The offset action the poll loop applies for this disposition.
    pub fn offset_action(&self) -> OffsetAction {
        match self {
            // Real progress, or commit-skip past unrecoverable poison / panic.
            RecordDisposition::Accepted
            | RecordDisposition::DecodeError
            | RecordDisposition::HandlerPanicked => OffsetAction::Advance,
            // Valid-but-unconsumable-now → set the partition commit ceiling and
            // keep consuming (redelivered after a fixed redeploy).
            RecordDisposition::RejectedSchema | RecordDisposition::DroppedForeignTenant => {
                OffsetAction::Withhold
            }
            // Deferred / transient → rewind and re-read soon.
            RecordDisposition::Backpressure | RecordDisposition::HandlerRetryLater => {
                OffsetAction::RewindRetry
            }
        }
    }

    /// Whether this disposition advanced the committed offset (`Advance`).
    pub fn committed(&self) -> bool {
        matches!(self.offset_action(), OffsetAction::Advance)
    }
}

/// Lower a partition's withheld commit ceiling to include `record_offset`, keeping
/// the LOWEST offset ever withheld on that partition. Returns the new ceiling.
/// Pure — the analog of the TS `loweredCeiling` / Go `committable` lowest-offset
/// rule, unit-testable without a broker.
fn lowered_ceiling(current: Option<i64>, record_offset: i64) -> i64 {
    match current {
        Some(c) => c.min(record_offset),
        None => record_offset,
    }
}

/// Whether committing a record at `record_offset` would cross the partition's
/// withheld ceiling. A commit advances PAST the record, so any record at or after
/// the withheld offset must NOT be committed (its commit would silently skip the
/// withheld one). Pure — mirrors the Go SDK's `committable` drop rule
/// (`m.Offset >= ceil`) and the TS `commitBlockedByWithheld`.
fn commit_blocked_by_withheld(ceiling: Option<i64>, record_offset: i64) -> bool {
    match ceiling {
        Some(c) => record_offset >= c,
        None => false,
    }
}

/// The Kafka consumer adapter: owns an rdkafka [`BaseConsumer`], subscribes to
/// the per-tenant wildcard topic, and drives the phase-2 pipeline.
///
/// Construct with [`KafkaTaskConsumer::new`], then call [`run`] for a blocking
/// poll loop (typically on a dedicated thread), or [`poll_once`] to drive a
/// single cycle from the caller's own loop.
///
/// [`run`]: KafkaTaskConsumer::run
/// [`poll_once`]: KafkaTaskConsumer::poll_once
pub struct KafkaTaskConsumer<H: MessageHandler> {
    config: KafkaConsumerConfig,
    consumer: BaseConsumer,
    handler: H,
    /// Cached capacity-pause state — avoids issuing a pause/resume RPC every poll
    /// (mirrors the Java `paused` flag and the Python `_paused` cache). This is the
    /// *capacity* (§1.5) pause of the whole assignment; it is independent of the
    /// per-partition withhold ceiling below.
    paused: bool,
    /// Per-partition commit CEILING: the LOWEST offset withheld (unknown schema
    /// major / foreign tenant) on that partition. No commit may cross it, so the
    /// withheld record is never committed (redelivered on the next rebalance /
    /// restart) while subsequent records still flow — the head-of-line-block fix.
    /// This does NOT pause the partition; consumption continues normally. Mirrors
    /// the Go SDK's `committable` ceiling and the TS `#withheld` map.
    withheld_ceilings: HashMap<(String, i32), i64>,
}

impl<H: MessageHandler> KafkaTaskConsumer<H> {
    /// Build the consumer from config + a live in-flight handler. Creates the
    /// rdkafka client and subscribes to the wildcard topic regex (§1.2).
    pub fn new(config: KafkaConsumerConfig, handler: H) -> Result<Self, KafkaError> {
        let consumer: BaseConsumer = config.to_client_config().create()?;
        consumer.subscribe(&[config.topic_regex().as_str()])?;
        Ok(Self {
            config,
            consumer,
            handler,
            paused: false,
            withheld_ceilings: HashMap::new(),
        })
    }

    /// Test/DI constructor: inject an already-built [`BaseConsumer`] (e.g. one
    /// pointed at a test broker) instead of creating one from config.
    pub fn with_consumer(
        config: KafkaConsumerConfig,
        consumer: BaseConsumer,
        handler: H,
    ) -> Result<Self, KafkaError> {
        consumer.subscribe(&[config.topic_regex().as_str()])?;
        Ok(Self {
            config,
            consumer,
            handler,
            paused: false,
            withheld_ceilings: HashMap::new(),
        })
    }

    /// Blocking poll loop. Returns `Ok(())` when `keep_running` flips to `false`
    /// (cooperative shutdown — set the flag from a signal handler / stop path,
    /// the analog of the Java `running` AtomicBool + `wakeup()`).
    ///
    /// `in_flight` is read live each cycle so backpressure reflects real
    /// concurrency.
    pub fn run<F, G>(&mut self, in_flight: F, keep_running: G) -> Result<(), KafkaError>
    where
        F: Fn() -> i64,
        G: Fn() -> bool,
    {
        while keep_running() {
            self.poll_once(&in_flight)?;
        }
        // Best-effort: commit nothing extra here; offsets are committed inline on
        // accept. Unsubscribe lets the group rebalance promptly on shutdown.
        self.consumer.unsubscribe();
        Ok(())
    }

    /// Drive one poll cycle: apply backpressure, poll once, process at most one
    /// record. Returns the disposition (or `None` when the poll timed out with no
    /// record). Exposed so a caller can own the loop (and interleave heartbeats).
    pub fn poll_once<F: Fn() -> i64>(
        &mut self,
        in_flight: &F,
    ) -> Result<Option<RecordDisposition>, KafkaError> {
        self.apply_backpressure(in_flight());

        // Extract everything we need from the BorrowedMessage *before* dropping
        // it: the `BorrowedMessage` immutably borrows `self.consumer`, so it must
        // not be held across the `&mut self` calls in `handle_payload` /
        // `pause_assignment`. We copy the payload and capture (topic, partition,
        // offset) for a manual commit.
        let extracted = match self.consumer.poll(self.config.poll_interval) {
            None => return Ok(None), // poll timed out, no record this cycle
            Some(Err(e)) => return Err(e),
            Some(Ok(borrowed)) => {
                let payload = borrowed.payload().map(|p| p.to_vec());
                (
                    payload,
                    borrowed.topic().to_string(),
                    borrowed.partition(),
                    borrowed.offset(),
                )
            }
        };
        let (payload, topic, partition, offset) = extracted;

        let disp = self.handle_payload(payload.as_deref(), in_flight());
        let key = (topic.clone(), partition);
        match disp.offset_action() {
            OffsetAction::Advance => {
                // Commit this record's offset (next offset = offset + 1),
                // mirroring the Java `commitSync(Map.of(tp, offset+1))`. Manual
                // commit (enable.auto.commit=false) → real progress (`Accepted`)
                // or commit-skip past unrecoverable poison / panic (fixture 30).
                //
                // BUT never commit a record at/above a withheld ceiling on this
                // partition (a lower record was withheld earlier): committing would
                // advance PAST — and thus silently skip — the withheld record. Below
                // the ceiling, advance normally by the record's OWN offset (never
                // clamped to ceiling-1, which would drop unprocessed records).
                if commit_blocked_by_withheld(self.withheld_ceilings.get(&key).copied(), offset) {
                    log::warn!(
                        "kafka commit withheld: {topic}[{partition}]@{offset} at/above \
                         partition ceiling {:?} → not committed (redelivered later)",
                        self.withheld_ceilings.get(&key)
                    );
                } else {
                    let mut tpl = TopicPartitionList::new();
                    tpl.add_partition_offset(&topic, partition, Offset::Offset(offset + 1))?;
                    self.consumer.commit(&tpl, CommitMode::Sync)?;
                }
            }
            OffsetAction::RewindRetry => {
                // Withhold a valid-but-deferred record (capacity / transient
                // handler error): rewind so it is re-read on the next cycle /
                // after capacity resume. The committed offset is NOT advanced, so
                // a later record can never commit past it.
                self.rewind_to(&topic, partition, offset);
            }
            OffsetAction::Withhold => {
                // Withhold a valid-but-unconsumable-now record (unknown schema
                // major / foreign tenant): record this partition's commit CEILING
                // (its LOWEST withheld offset) and KEEP consuming. No later commit
                // crosses the ceiling, so the withheld record is redelivered on the
                // next rebalance / restart while subsequent records still flow — no
                // head-of-line block, no partition pause, no seek-back.
                let new_ceiling =
                    lowered_ceiling(self.withheld_ceilings.get(&key).copied(), offset);
                self.withheld_ceilings.insert(key, new_ceiling);
                log::warn!(
                    "kafka withhold: {topic}[{partition}]@{offset} → commit ceiling {new_ceiling} \
                     (still consuming; record redelivered later)"
                );
            }
        }
        Ok(Some(disp))
    }

    /// Rewind the consumer position on one partition back to `offset` so that
    /// record is re-read. Best-effort — a seek failure is logged, not fatal (the
    /// worst case degrades to the pre-fix behavior for that one record).
    fn rewind_to(&self, topic: &str, partition: i32, offset: i64) {
        if let Err(e) = self.consumer.seek(
            topic,
            partition,
            Offset::Offset(offset),
            Duration::from_secs(5),
        ) {
            log::warn!(
                "kafka withhold: seek {topic}[{partition}]@{offset} failed: {e}; \
                 committed offset unchanged"
            );
        }
    }

    /// Decode an owned payload, run it through the pipeline, and (on accept)
    /// dispatch to the handler. Decoupled from the `BorrowedMessage` so it can
    /// take `&mut self`. Does NOT commit — the caller commits on
    /// [`RecordDisposition::committed`].
    fn handle_payload(&mut self, payload: Option<&[u8]>, in_flight: i64) -> RecordDisposition {
        let payload = match payload {
            Some(p) if !p.is_empty() => p,
            _ => {
                // Empty tombstone-style record — undecodable poison. Commit-skip
                // past it (fixture 30) so it cannot head-of-line block; log so the
                // skip is visible, not silent.
                log::warn!("kafka decode: empty/tombstone record → commit-skip past it");
                return RecordDisposition::DecodeError;
            }
        };

        let decoded: DispatchMessage = match serde_json::from_slice(payload) {
            Ok(d) => d,
            Err(e) => {
                // Malformed JSON — undecodable poison. Commit-skip past it
                // (fixture 30): re-delivery only re-fails, so advancing avoids a
                // permanent one-message head-of-line block. Log ERROR (Java
                // DROP_TERMINAL logs ERROR) — never a silent drop.
                log::error!("kafka decode error: {e} → commit-skip past poison record");
                return RecordDisposition::DecodeError;
            }
        };

        let record = decoded.to_record();
        match run_pipeline(
            &record,
            in_flight,
            self.config.max_concurrent,
            &self.config.tenant_id,
        ) {
            MessageOutcome::RejectSchema => {
                log::warn!(
                    "kafka reject: unknown schemaVersion major {:?} for task {} → \
                     withhold (commit ceiling; keep consuming) (§A)",
                    decoded.schema_version,
                    decoded.task_id_str()
                );
                RecordDisposition::RejectedSchema
            }
            MessageOutcome::DropForeignTenant => {
                log::warn!(
                    "kafka drop: foreign tenant {:?} (worker tenant {}) for task {} → \
                     withhold (commit ceiling; keep consuming) (§1.9)",
                    decoded.tenant_id,
                    self.config.tenant_id,
                    decoded.task_id_str()
                );
                RecordDisposition::DroppedForeignTenant
            }
            MessageOutcome::Accept { paused: true } => {
                // Valid message but we are at capacity: pause the whole assignment
                // and defer (offset withheld + rewound → re-read after resume).
                // Mirrors the Java/Python backpressure path.
                log::warn!(
                    "kafka backpressure: at capacity ({}/{}) → defer task {} (rewind)",
                    in_flight,
                    self.config.max_concurrent,
                    decoded.task_id_str()
                );
                self.pause_assignment();
                RecordDisposition::Backpressure
            }
            MessageOutcome::Accept { paused: false } => self.dispatch_to_handler(&decoded),
        }
    }

    /// Dispatch an accepted, in-tenant message to the handler, guarding the call
    /// with [`std::panic::catch_unwind`] so a panicking business handler **never**
    /// kills the poll loop / process. `on_accepted` takes `&mut self.handler`, so
    /// the closure is wrapped in [`AssertUnwindSafe`] (the poll loop owns the
    /// handler and drives it one message at a time — there is no shared mutable
    /// state left inconsistent by a caught unwind).
    ///
    /// * `Ok(())`  → `Accepted` (commit).
    /// * `Err(_)`  → `HandlerRetryLater` (rewind + re-read; transient failure).
    /// * panic     → `HandlerPanicked` (commit-skip, logged ERROR): the panic is
    ///   contained, the next record is processed, and the process stays up. The
    ///   handler bridge is responsible for the fail REPORT before the offset moves
    ///   on; a panic escaping the bridge is treated as poison rather than
    ///   re-panic-looped.
    fn dispatch_to_handler(&mut self, decoded: &DispatchMessage) -> RecordDisposition {
        let handler = &mut self.handler;
        let outcome = std::panic::catch_unwind(AssertUnwindSafe(|| handler.on_accepted(decoded)));
        match outcome {
            Ok(Ok(())) => RecordDisposition::Accepted,
            Ok(Err(msg)) => {
                log::warn!(
                    "kafka handler: task {} returned Err ({msg}) → rewind + retry-later",
                    decoded.task_id_str()
                );
                RecordDisposition::HandlerRetryLater
            }
            Err(panic) => {
                log::error!(
                    "kafka handler PANICKED on task {}: {} → contained; commit-skip \
                     (handler bridge must report fail)",
                    decoded.task_id_str(),
                    panic_message(panic.as_ref())
                );
                RecordDisposition::HandlerPanicked
            }
        }
    }

    /// Capacity-aware partition pause/resume (§1.5). Pause the whole assignment
    /// when in-flight has reached the cap; resume once it drains. The `paused`
    /// cache avoids redundant RPCs (mirrors Java `paused` / Python `_paused`).
    fn apply_backpressure(&mut self, in_flight: i64) {
        let should_pause = in_flight >= self.config.max_concurrent;
        if should_pause && !self.paused {
            self.pause_assignment();
        } else if !should_pause && self.paused {
            self.resume_assignment();
        }
    }

    /// Pause every currently-assigned partition.
    fn pause_assignment(&mut self) {
        if let Ok(tpl) = self.consumer.assignment() {
            if tpl.count() > 0 {
                let _ = self.consumer.pause(&tpl);
                self.paused = true;
            }
        }
    }

    /// Resume every currently-assigned partition after a capacity pause. Withheld
    /// records no longer pause their partition (they set a commit ceiling and keep
    /// consuming), so this simply resumes the whole assignment — the analog of the
    /// Java/Python capacity resume.
    fn resume_assignment(&mut self) {
        if let Ok(tpl) = self.consumer.assignment() {
            if tpl.count() > 0 {
                let _ = self.consumer.resume(&tpl);
            }
            self.paused = false;
        }
    }

    /// Current cached pause decision (diagnostics / tests).
    pub fn is_paused(&self) -> bool {
        self.paused
    }

    /// The live assignment (diagnostics / tests).
    pub fn assignment(&self) -> Result<TopicPartitionList, KafkaError> {
        self.consumer.assignment()
    }
}

// ───────────────────────────────────────────────────────────────────────────
// Integration test — mirrors the TS/Go/Python behavior, gated on a live broker.
// Runs only in CI where KAFKA_BOOTSTRAP points at a real cluster; a no-broker
// environment returns early so `cargo test --features kafka` stays green locally.
// ───────────────────────────────────────────────────────────────────────────
#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicI64, Ordering};
    use std::sync::Arc;
    use std::time::Duration;

    fn test_config(bootstrap: String) -> KafkaConsumerConfig {
        KafkaConsumerConfig {
            bootstrap_servers: bootstrap,
            tenant_id: "tenant-a".to_string(),
            worker_code: "worker-1".to_string(),
            max_concurrent: 4,
            poll_interval: Duration::from_millis(500),
            security_protocol: None,
            sasl_mechanism: None,
            sasl_username: None,
            sasl_password: None,
        }
    }

    // ── Pure config tests (no broker) ─────────────────────────────────────

    #[test]
    fn topic_regex_is_node_direct_per_worker() {
        let c = test_config("localhost:9092".to_string());
        // node-direct: batch.task.dispatch.<workerType>.node.<workerCode>, the
        // `.*` covering any base workerType (workerType-agnostic, #2).
        assert_eq!(
            c.topic_regex(),
            r"^batch\.task\.dispatch\..*\.node\.worker-1"
        );
    }

    #[test]
    fn group_id_is_per_worker() {
        let c = test_config("localhost:9092".to_string());
        assert_eq!(c.group_id(), "g-sdk-tenant-a-worker-1");
    }

    #[test]
    fn plaintext_when_no_sasl() {
        let c = test_config("localhost:9092".to_string());
        let cfg = c.to_client_config();
        assert_eq!(cfg.get("enable.auto.commit"), Some("false"));
        assert_eq!(cfg.get("security.protocol"), None);
        assert_eq!(cfg.get("sasl.mechanism"), None);
    }

    #[test]
    fn sasl_scram_defaults_to_sha512() {
        let mut c = test_config("localhost:9092".to_string());
        c.security_protocol = Some("SASL_SSL".to_string());
        c.sasl_username = Some("svc-tenant-a".to_string());
        c.sasl_password = Some("secret".to_string());
        let cfg = c.to_client_config();
        assert_eq!(cfg.get("security.protocol"), Some("SASL_SSL"));
        assert_eq!(cfg.get("sasl.mechanism"), Some("SCRAM-SHA-512"));
        assert_eq!(cfg.get("sasl.username"), Some("svc-tenant-a"));
        assert_eq!(cfg.get("sasl.password"), Some("secret"));
    }

    #[test]
    fn worker_code_is_regex_escaped() {
        let mut c = test_config("localhost:9092".to_string());
        c.worker_code = "w.o+rker".to_string();
        // dots and '+' in the worker code must be escaped so the node-direct
        // subscription matches literally.
        assert_eq!(
            c.topic_regex(),
            r"^batch\.task\.dispatch\..*\.node\.w\.o\+rker"
        );
    }

    #[test]
    fn dispatch_message_ignores_unknown_fields() {
        // taskId is an int64 number on the wire; taskType is the v1 alias for
        // the v2 `workerType` routing key.
        let raw = br#"{"taskId":1,"tenantId":"tenant-a","schemaVersion":"v1",
            "taskType":"import","partitionInvocationId":"pinv-9",
            "idempotencyKey":"k","parameters":{"a":1},"somethingNew":42}"#;
        let m: DispatchMessage = serde_json::from_slice(raw).expect("decode");
        assert_eq!(m.task_id, 1);
        assert_eq!(m.task_id_str(), "1");
        assert_eq!(m.tenant_id, "tenant-a");
        assert_eq!(m.schema_version.as_deref(), Some("v1"));
        assert_eq!(m.task_type, "import");
        assert_eq!(m.partition_invocation_id.as_deref(), Some("pinv-9"));
        // unknown/extra fields are preserved in `extra`, not rejected.
        assert!(m.extra.contains_key("idempotencyKey"));
        assert!(m.extra.contains_key("somethingNew"));
    }

    #[test]
    fn dispatch_message_binds_worker_type() {
        // v2 envelope: routing key is `workerType` (taskType removed).
        let raw =
            br#"{"taskId":7,"tenantId":"tenant-a","schemaVersion":"v1","workerType":"export"}"#;
        let m: DispatchMessage = serde_json::from_slice(raw).expect("decode");
        assert_eq!(m.task_id, 7);
        assert_eq!(m.task_type, "export");
    }

    // ── Offset-disposition contract (the P0 withhold-vs-commit-skip fix) ───

    #[test]
    fn offset_action_advances_only_on_progress_or_poison() {
        // Real progress commits; undecodable poison AND a contained handler panic
        // commit-skip (fixture 30) so one bad record can't head-of-line block.
        assert_eq!(
            RecordDisposition::Accepted.offset_action(),
            OffsetAction::Advance
        );
        assert_eq!(
            RecordDisposition::DecodeError.offset_action(),
            OffsetAction::Advance
        );
        assert_eq!(
            RecordDisposition::HandlerPanicked.offset_action(),
            OffsetAction::Advance
        );
        // committed() is the Advance shorthand.
        assert!(RecordDisposition::Accepted.committed());
        assert!(RecordDisposition::DecodeError.committed());
        assert!(RecordDisposition::HandlerPanicked.committed());
    }

    #[test]
    fn offset_action_withholds_unconsumable_records_via_ceiling() {
        // Unknown schema major / foreign tenant: Withhold — record the partition's
        // commit CEILING and KEEP consuming (no seek, no pause), so a later commit
        // never crosses (skips past) the withheld record AND subsequent records
        // still flow (no head-of-line block). This is the HOL-block fix: the old
        // RewindBlock paused the partition forever.
        assert_eq!(
            RecordDisposition::RejectedSchema.offset_action(),
            OffsetAction::Withhold
        );
        assert_eq!(
            RecordDisposition::DroppedForeignTenant.offset_action(),
            OffsetAction::Withhold
        );
        // These must NOT count as committed.
        assert!(!RecordDisposition::RejectedSchema.committed());
        assert!(!RecordDisposition::DroppedForeignTenant.committed());
    }

    // ── Pure commit-ceiling logic (the HOL-block / no-skip invariant) ──────

    #[test]
    fn lowered_ceiling_keeps_lowest_withheld_offset() {
        // First withhold on a partition sets the ceiling.
        assert_eq!(lowered_ceiling(None, 7), 7);
        // A later, LOWER withhold lowers it (the lowest offer must win so nothing
        // at/after it is ever committed).
        assert_eq!(lowered_ceiling(Some(7), 3), 3);
        // A later, HIGHER withhold does NOT raise it.
        assert_eq!(lowered_ceiling(Some(3), 9), 3);
        assert_eq!(lowered_ceiling(Some(3), 3), 3);
    }

    #[test]
    fn commit_blocked_only_at_or_above_ceiling() {
        // No ceiling → nothing is blocked.
        assert!(!commit_blocked_by_withheld(None, 0));
        assert!(!commit_blocked_by_withheld(None, 100));
        // Below the ceiling → commit allowed (advance by its OWN offset; the
        // withheld record at the ceiling is never reached by such a commit).
        assert!(!commit_blocked_by_withheld(Some(5), 4));
        // AT the ceiling → blocked (committing offset+1 would skip the withheld
        // record itself).
        assert!(commit_blocked_by_withheld(Some(5), 5));
        // ABOVE the ceiling → blocked (a later record must not commit past the
        // withheld one — the exact bug the ceiling prevents; NOT clamped to
        // ceiling-1, which would drop unprocessed records).
        assert!(commit_blocked_by_withheld(Some(5), 6));
    }

    #[test]
    fn offset_action_rewinds_deferred_records_without_blocking() {
        // Capacity backpressure / transient handler error: rewind + re-read, no
        // permanent partition park.
        assert_eq!(
            RecordDisposition::Backpressure.offset_action(),
            OffsetAction::RewindRetry
        );
        assert_eq!(
            RecordDisposition::HandlerRetryLater.offset_action(),
            OffsetAction::RewindRetry
        );
        assert!(!RecordDisposition::Backpressure.committed());
        assert!(!RecordDisposition::HandlerRetryLater.committed());
    }

    #[test]
    fn config_debug_redacts_sasl_password() {
        let mut c = test_config("localhost:9092".to_string());
        c.sasl_username = Some("svc-tenant-a".to_string());
        c.sasl_password = Some("super-secret-pw".to_string());
        let dbg = format!("{c:?}");
        // The password must never appear; the username (an identity) may.
        assert!(
            !dbg.contains("super-secret-pw"),
            "Debug leaked sasl_password: {dbg}"
        );
        assert!(dbg.contains("***"), "redaction marker missing: {dbg}");
        assert!(
            dbg.contains("svc-tenant-a"),
            "username should still be shown: {dbg}"
        );
    }

    // ── Live-broker integration test (CI only) ────────────────────────────

    /// Collects accepted messages so the test can assert delivery.
    struct CollectingHandler {
        accepted: Arc<std::sync::Mutex<Vec<String>>>,
    }
    impl MessageHandler for CollectingHandler {
        fn on_accepted(&mut self, msg: &DispatchMessage) -> Result<(), String> {
            self.accepted.lock().unwrap().push(msg.task_id_str());
            Ok(())
        }
    }

    fn assigned_topics(tpl: &TopicPartitionList) -> Vec<String> {
        tpl.elements()
            .into_iter()
            .map(|elem| elem.topic().to_string())
            .collect()
    }

    fn send_record(producer: &rdkafka::producer::FutureProducer, topic: &str, payload: &[u8]) {
        let delivery = futures_executor::block_on(producer.send(
            rdkafka::producer::FutureRecord::<(), [u8]>::to(topic).payload(payload),
            Duration::from_secs(10),
        ));
        if let Err((err, _message)) = delivery {
            panic!("produce to {topic} failed: {err:?}");
        }
    }

    #[test]
    fn end_to_end_consume_against_real_broker() {
        // Env gate: only runs in CI with a broker (mirrors the TS/Go/Python
        // integration gate). No broker → return green.
        let bootstrap = match std::env::var("KAFKA_BOOTSTRAP") {
            Ok(b) if !b.trim().is_empty() => b,
            _ => return,
        };

        use rdkafka::admin::{AdminClient, AdminOptions, NewTopic, TopicReplication};
        use rdkafka::client::DefaultClientContext;
        use rdkafka::producer::FutureProducer;
        use rdkafka::types::RDKafkaErrorCode;

        let mut cfg = test_config(bootstrap.clone());
        cfg.worker_code = format!(
            "worker-live-{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        );
        // node-direct topic the worker's subscription regex matches (#2):
        // batch.task.dispatch.<workerType>.node.<workerCode>.
        let topic = format!("batch.task.dispatch.import.node.{}", cfg.worker_code);

        let admin: AdminClient<DefaultClientContext> = ClientConfig::new()
            .set("bootstrap.servers", &bootstrap)
            .create()
            .expect("admin");
        let new_topic = NewTopic::new(&topic, 1, TopicReplication::Fixed(1));
        let topic_results = futures_executor::block_on(admin.create_topics(
            &[new_topic],
            &AdminOptions::new().operation_timeout(Some(Duration::from_secs(10))),
        ))
        .expect("create topic");
        for result in topic_results {
            match result {
                Ok(_) => {}
                Err((_, RDKafkaErrorCode::TopicAlreadyExists)) => {}
                Err((name, err)) => panic!("create topic {name} failed: {err:?}"),
            }
        }

        // Seed first so the regex consumer sees the topic before subscribing.
        // Real dispatch records are produced only after the consumer is polling;
        // production config intentionally uses `auto.offset.reset=latest`.
        let producer: FutureProducer = ClientConfig::new()
            .set("bootstrap.servers", &bootstrap)
            .create()
            .expect("producer");
        send_record(
            &producer,
            &topic,
            br#"{"taskId":0,"tenantId":"tenant-a","schemaVersion":"v1","workerType":"import"}"#,
        );

        let accepted = Arc::new(std::sync::Mutex::new(Vec::<String>::new()));
        let handler = CollectingHandler {
            accepted: accepted.clone(),
        };
        let mut consumer_config = cfg.to_client_config();
        consumer_config.set("auto.offset.reset", "earliest");
        let base_consumer: BaseConsumer = consumer_config.create().expect("base consumer");
        let mut consumer =
            KafkaTaskConsumer::with_consumer(cfg, base_consumer, handler).expect("consumer");
        let in_flight = Arc::new(AtomicI64::new(0));
        let if_read = move || in_flight.load(Ordering::SeqCst);

        let assignment_deadline = std::time::Instant::now() + Duration::from_secs(10);
        while std::time::Instant::now() < assignment_deadline {
            let _ = consumer.poll_once(&if_read).expect("assignment poll");
            if !consumer
                .assignment()
                .expect("assignment")
                .elements_for_topic(&topic)
                .is_empty()
            {
                break;
            }
        }
        let assignment = consumer.assignment().expect("assignment");
        assert!(
            !assignment.elements_for_topic(&topic).is_empty(),
            "consumer must be assigned to {topic} before producing live records; assigned={:?}",
            assigned_topics(&assignment)
        );

        // Produce good(1), foreign-tenant(2), good(3) on the SAME (single)
        // partition. A withhold (foreign-tenant / bad-schema) records a per-partition
        // commit CEILING and KEEPS consuming — it does NOT pause or seek back — so a
        // record AFTER the withheld one must still be delivered. Task 3 proves the
        // head-of-line block is gone; task 2 must never be dispatched (§1.9). This is
        // the exact bug the old RewindBlock (pause-forever) plumbing caused.
        // RejectedSchema is the *same* Withhold plumbing as DroppedForeignTenant
        // (see `offset_action`); its unit coverage is
        // `offset_action_withholds_unconsumable_records_via_ceiling`.
        let good1 =
            br#"{"taskId":1,"tenantId":"tenant-a","schemaVersion":"v1","workerType":"import"}"#;
        let foreign =
            br#"{"taskId":2,"tenantId":"tenant-b","schemaVersion":"v1","workerType":"import"}"#;
        let good3 =
            br#"{"taskId":3,"tenantId":"tenant-a","schemaVersion":"v1","workerType":"import"}"#;
        for payload in [good1.as_slice(), foreign.as_slice(), good3.as_slice()] {
            send_record(&producer, &topic, payload);
        }

        // Poll for up to ~15s or until all decisions have been observed.
        let deadline = std::time::Instant::now() + Duration::from_secs(15);
        let mut dispositions = Vec::<String>::new();
        let mut saw_good1 = false;
        let mut saw_good3 = false;
        let mut saw_foreign = false;
        while std::time::Instant::now() < deadline {
            let disp = consumer.poll_once(&if_read).expect("poll");
            dispositions.push(format!("{disp:?}"));
            match disp {
                Some(RecordDisposition::Accepted) => {
                    let acc = accepted.lock().unwrap();
                    saw_good1 = acc.iter().any(|t| t == "1");
                    saw_good3 = acc.iter().any(|t| t == "3");
                }
                Some(RecordDisposition::DroppedForeignTenant) => saw_foreign = true,
                _ => {}
            }
            if saw_good1 && saw_good3 && saw_foreign {
                break;
            }
        }

        let got = accepted.lock().unwrap().clone();
        assert!(
            saw_good1 && got.contains(&"1".to_string()),
            "good record 1 must be accepted; got={got:?}; dispositions={dispositions:?}"
        );
        assert!(
            saw_foreign,
            "foreign-tenant record must be dropped; dispositions={dispositions:?}"
        );
        // The head-of-line-block fix: a record AFTER the withheld foreign-tenant one
        // is STILL delivered (the withhold set a commit ceiling, it did not pause).
        assert!(
            saw_good3 && got.contains(&"3".to_string()),
            "good record 3 (after the withheld record) must still be delivered — no \
             head-of-line block; got={got:?}; dispositions={dispositions:?}"
        );
        assert!(
            !got.contains(&"2".to_string()),
            "foreign-tenant record must be dropped (§1.9)"
        );
    }
}
