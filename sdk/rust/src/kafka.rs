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
//! * **Withhold**：未知协议版本、外租户及暂时失败的消息仅暂停当前分区，
//!   回退到未确认位置并在短暂等待后重试，其他分区继续消费。分区提交不得
//!   跨过未确认消息；重分配时从 broker 已提交位置恢复。
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

use std::collections::{HashMap, HashSet};
use std::panic::AssertUnwindSafe;
use std::time::{Duration, Instant};

use rdkafka::config::ClientConfig;
use rdkafka::consumer::{BaseConsumer, CommitMode, Consumer as RdConsumer};
use rdkafka::error::KafkaError;
use rdkafka::message::Message;
use rdkafka::{Offset, TopicPartitionList};

use crate::client::consumer::{run_pipeline, MessageOutcome, TaskRecord};

const WITHHOLD_RETRY_DELAY: Duration = Duration::from_secs(2);

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
            "^batch\\.task\\.dispatch\\..*\\.node\\.{}$",
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
            // 新消费组和失效位点从仍保留的最早消息恢复，避免转交时漏读。
            .set("auto.offset.reset", "earliest");

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
        let task_id_text = self.task_id_str();
        TaskRecord::new(
            &task_id_text,
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
    /// 未知协议大版本：保留位点，暂停该分区并等待重读（§A）。
    RejectedSchema,
    /// 外租户消息：保留位点，暂停该分区并等待重读（§1.9）。
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
    /// Transient retry: rewind the position to this record so it is re-read next
    /// cycle / after capacity resume. Committed offset is NOT advanced.
    RewindRetry,
    /// 保留分区最低未确认位点，暂停该分区并等待重试。
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
#[cfg(test)]
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
    /// 每个分区保留最低未确认位点，确认成功或转交后再清理。
    withheld_ceilings: HashMap<(String, i32), i64>,
    // 每个暂扣分区只保留一个重试时间。
    withheld_retries: HashMap<(String, i32), Instant>,
    // 消费处理前已确认 broker 有可恢复位置的分区。
    initialized_partitions: HashSet<(String, i32)>,
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
            withheld_retries: HashMap::new(),
            initialized_partitions: HashSet::new(),
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
            withheld_retries: HashMap::new(),
            initialized_partitions: HashSet::new(),
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
        self.run_controlled(in_flight, || false, keep_running)
    }

    /// 带平台暂停状态门控的阻塞轮询循环。
    /// 心跳可暂停任务领取而不取消订阅；恢复后从同一消费组位置继续轮询。
    pub fn run_controlled<F, P, G>(
        &mut self,
        in_flight: F,
        platform_paused: P,
        keep_running: G,
    ) -> Result<(), KafkaError>
    where
        F: Fn() -> i64,
        P: Fn() -> bool,
        G: Fn() -> bool,
    {
        while keep_running() {
            if platform_paused() {
                std::thread::sleep(Duration::from_millis(50));
                continue;
            }
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
        self.resume_withheld_partitions()?;

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

        let key = (topic.clone(), partition);
        if let Some(&ceiling) = self.withheld_ceilings.get(&key) {
            if offset > ceiling {
                // 分区转交后，仅在 broker 已提交位置越过旧上限时清理。
                let committed = match self.committed_position(&topic, partition) {
                    Ok(position) => position,
                    Err(error) => {
                        // 本次 poll 已推进本地游标；查询失败仍须回退到上限。
                        self.withhold_partition(&topic, partition, ceiling)?;
                        log::warn!("分区提交位置查询失败，等待重试: topic={topic}, partition={partition}, error={error}");
                        return Ok(None);
                    }
                };
                if matches!(committed, Offset::Offset(value) if value > ceiling) {
                    self.withheld_ceilings.remove(&key);
                    self.withheld_retries.remove(&key);
                } else {
                    self.withhold_partition(&topic, partition, ceiling)?;
                    return Ok(None);
                }
            }
        }
        if let Err(error) = self.ensure_recovery_position(&topic, partition, offset) {
            // 即使调用方选择继续轮询，也不能越过本次未能建立恢复位置的消息。
            self.withhold_partition(&topic, partition, offset)?;
            log::warn!("分区恢复位置确认失败，等待重试: topic={topic}, partition={partition}, error={error}");
            return Ok(None);
        }
        let disp = self.handle_payload(payload.as_deref(), in_flight());
        match disp.offset_action() {
            OffsetAction::Advance => {
                // Commit this record's offset (next offset = offset + 1),
                // mirroring the Java `commitSync(Map.of(tp, offset+1))`. Manual
                // commit (enable.auto.commit=false) → real progress (`Accepted`)
                // or commit-skip past unrecoverable poison / panic (fixture 30).
                //
                // 重读上限自身并处理成功后才允许跨过它；更高位点须先核对 broker。
                let withheld_ceiling = self.withheld_ceilings.get(&key).copied();
                if withheld_ceiling.is_some_and(|ceiling| offset > ceiling) {
                    log::warn!(
                        "kafka commit withheld: {topic}[{partition}]@{offset} at/above \
                         partition ceiling {:?} → not committed (redelivered later)",
                        withheld_ceiling
                    );
                } else {
                    let mut tpl = TopicPartitionList::new();
                    tpl.add_partition_offset(&topic, partition, Offset::Offset(offset + 1))?;
                    if let Err(error) = self.consumer.commit(&tpl, CommitMode::Sync) {
                        self.withhold_partition(&topic, partition, offset)?;
                        log::warn!("分区提交失败，等待重试: topic={topic}, partition={partition}, error={error}");
                        return Ok(None);
                    }
                    if withheld_ceiling == Some(offset) {
                        self.withheld_ceilings.remove(&key);
                        self.withheld_retries.remove(&key);
                    }
                }
            }
            OffsetAction::RewindRetry => {
                // Retry a valid-but-deferred record (capacity / transient handler
                // error): rewind so it is re-read on the next cycle /
                // after capacity resume. The committed offset is NOT advanced, so
                // a later record can never commit past it.
                if disp == RecordDisposition::Backpressure {
                    self.rewind_to(&topic, partition, offset)?;
                } else {
                    self.withhold_partition(&topic, partition, offset)?;
                }
            }
            OffsetAction::Withhold => {
                // 仅暂停当前分区，其他分区继续消费。
                self.withhold_partition(&topic, partition, offset)?;
            }
        }
        Ok(Some(disp))
    }

    /// 回退失败时终止本轮处理，避免后续提交跨越未确认消息。
    fn rewind_to(&self, topic: &str, partition: i32, offset: i64) -> Result<(), KafkaError> {
        self.consumer.seek(
            topic,
            partition,
            Offset::Offset(offset),
            Duration::from_secs(5),
        )
    }

    fn ensure_recovery_position(
        &mut self,
        topic: &str,
        partition: i32,
        offset: i64,
    ) -> Result<(), KafkaError> {
        let key = (topic.to_string(), partition);
        if self.initialized_partitions.contains(&key) {
            return Ok(());
        }
        if self.committed_position(topic, partition)? == Offset::Invalid {
            // 业务处理前保存当前 offset（不是 offset + 1）；自定义 latest
            // 消费者在处理中途发生转交时也可从本条重读。
            let mut start = TopicPartitionList::new();
            start.add_partition_offset(topic, partition, Offset::Offset(offset))?;
            self.consumer.commit(&start, CommitMode::Sync)?;
        }
        self.initialized_partitions.insert(key);
        Ok(())
    }

    fn committed_position(&self, topic: &str, partition: i32) -> Result<Offset, KafkaError> {
        let mut requested = TopicPartitionList::new();
        requested.add_partition(topic, partition);
        let committed = self
            .consumer
            .committed_offsets(requested, Duration::from_secs(5))?;
        let entry = committed
            .find_partition(topic, partition)
            .ok_or_else(|| KafkaError::Seek("缺少分区提交位置".into()))?;
        entry.error()?;
        Ok(entry.offset())
    }

    fn withhold_partition(
        &mut self,
        topic: &str,
        partition: i32,
        offset: i64,
    ) -> Result<(), KafkaError> {
        let key = (topic.to_string(), partition);
        let ceiling = lowered_ceiling(self.withheld_ceilings.get(&key).copied(), offset);
        self.withheld_ceilings.insert(key.clone(), ceiling);
        let mut partitions = TopicPartitionList::new();
        partitions.add_partition(topic, partition);
        self.consumer.pause(&partitions)?;
        self.rewind_to(topic, partition, ceiling)?;
        self.withheld_retries
            .insert(key, Instant::now() + WITHHOLD_RETRY_DELAY);
        log::warn!(
            "kafka partition withheld: topic={topic}, partition={partition}, offset={ceiling}"
        );
        Ok(())
    }

    fn resume_withheld_partitions(&mut self) -> Result<(), KafkaError> {
        let assignment = self.consumer.assignment()?;
        self.initialized_partitions
            .retain(|(topic, partition)| assignment.find_partition(topic, *partition).is_some());
        self.withheld_ceilings
            .retain(|(topic, partition), _| assignment.find_partition(topic, *partition).is_some());
        self.withheld_retries
            .retain(|(topic, partition), _| assignment.find_partition(topic, *partition).is_some());
        if self.paused {
            return Ok(());
        }
        let now = Instant::now();
        let ready: Vec<_> = self
            .withheld_retries
            .iter()
            .filter(|(_, deadline)| **deadline <= now)
            .map(|(key, _)| key.clone())
            .collect();
        for key in ready {
            let mut partitions = TopicPartitionList::new();
            partitions.add_partition(&key.0, key.1);
            self.consumer.resume(&partitions)?;
            self.withheld_retries.remove(&key);
        }
        Ok(())
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

    /// 容量恢复时只恢复未处于暂扣等待的分区。
    fn resume_assignment(&mut self) {
        if let Ok(tpl) = self.consumer.assignment() {
            let mut ready = TopicPartitionList::new();
            for part in tpl.elements() {
                let key = (part.topic().to_string(), part.partition());
                if !self.withheld_retries.contains_key(&key) {
                    ready.add_partition(part.topic(), part.partition());
                }
            }
            if ready.count() > 0 {
                let _ = self.consumer.resume(&ready);
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
// Runs only in CI where BATCH_SDK_KAFKA_BOOTSTRAP points at a real cluster; a no-broker
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
            r"^batch\.task\.dispatch\..*\.node\.worker-1$"
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
        assert_eq!(cfg.get("auto.offset.reset"), Some("earliest"));
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
            r"^batch\.task\.dispatch\..*\.node\.w\.o\+rker$"
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

    #[test]
    fn latest_initial_position_is_durable_before_handler() {
        use rdkafka::mocking::MockCluster;
        use rdkafka::producer::{BaseProducer, BaseRecord, Producer};
        use std::time::Instant;

        struct PositionCheckingHandler {
            observer: BaseConsumer,
            topic: String,
            observed: Arc<std::sync::Mutex<Option<Offset>>>,
        }
        impl MessageHandler for PositionCheckingHandler {
            fn on_accepted(&mut self, _: &DispatchMessage) -> Result<(), String> {
                let mut query = TopicPartitionList::new();
                query.add_partition(&self.topic, 0);
                let offsets = self
                    .observer
                    .committed_offsets(query, Duration::from_secs(5))
                    .map_err(|e| e.to_string())?;
                *self.observed.lock().unwrap() =
                    offsets.find_partition(&self.topic, 0).map(|p| p.offset());
                Ok(())
            }
        }

        let cluster = MockCluster::new(1).expect("mock cluster");
        let topic = "batch.task.dispatch.import.node.worker-1";
        cluster.create_topic(topic, 1, 1).expect("topic");
        let cfg = test_config(cluster.bootstrap_servers());
        let observer: BaseConsumer = cfg.to_client_config().create().unwrap();
        let observed = Arc::new(std::sync::Mutex::new(None));
        let handler = PositionCheckingHandler {
            observer,
            topic: topic.to_string(),
            observed: observed.clone(),
        };
        let base: BaseConsumer = cfg.to_client_config().create().unwrap();
        let mut consumer = KafkaTaskConsumer::with_consumer(cfg, base, handler).unwrap();
        consumer.consumer.unsubscribe();
        let mut assignment = TopicPartitionList::new();
        assignment
            .add_partition_offset(topic, 0, Offset::End)
            .unwrap();
        consumer.consumer.assign(&assignment).unwrap();
        // 在发布之前解析 latest 起点，确保首次消息由末尾之后送达。
        for _ in 0..5 {
            let _ = consumer.consumer.poll(Duration::from_millis(200));
        }
        let producer: BaseProducer = ClientConfig::new()
            .set("bootstrap.servers", cluster.bootstrap_servers())
            .create()
            .unwrap();
        producer
            .send(
                BaseRecord::<(), str>::to(topic)
                    .partition(0)
                    .payload(r#"{"taskId":4,"tenantId":"tenant-a","schemaVersion":"v1"}"#),
            )
            .unwrap();
        producer.flush(Duration::from_secs(5)).unwrap();
        use rdkafka::types::{RDKafkaApiKey, RDKafkaRespErr};
        cluster.request_errors(
            RDKafkaApiKey::OffsetFetch,
            &[RDKafkaRespErr::RD_KAFKA_RESP_ERR_GROUP_AUTHORIZATION_FAILED],
        );
        let deadline = Instant::now() + Duration::from_secs(8);
        loop {
            assert!(Instant::now() < deadline, "未触发初始化位点查询失败");
            consumer
                .poll_once(&|| 0)
                .expect("位点查询失败应暂扣当前分区");
            if consumer
                .withheld_ceilings
                .contains_key(&(topic.to_string(), 0))
            {
                break;
            }
        }
        assert!(
            observed.lock().unwrap().is_none(),
            "查询失败时不能进入处理器"
        );
        assert_eq!(
            consumer.withheld_ceilings.get(&(topic.to_string(), 0)),
            Some(&0)
        );
        cluster.clear_request_errors(RDKafkaApiKey::OffsetFetch);
        let deadline = Instant::now() + Duration::from_secs(8);
        while observed.lock().unwrap().is_none() {
            assert!(Instant::now() < deadline, "未收到消息");
            consumer.poll_once(&|| 0).unwrap();
        }
        assert_eq!(*observed.lock().unwrap(), Some(Offset::Offset(0)));
    }

    #[test]
    fn commit_failure_retains_record_for_retry() {
        use rdkafka::mocking::MockCluster;
        use rdkafka::producer::DefaultProducerContext;
        use rdkafka::producer::{BaseProducer, BaseRecord, Producer};
        use rdkafka::types::{RDKafkaApiKey, RDKafkaRespErr};
        use std::sync::atomic::AtomicUsize;
        use std::time::Instant;

        struct CommitFault<'a> {
            cluster: &'a MockCluster<'static, DefaultProducerContext>,
            calls: Arc<AtomicUsize>,
        }
        impl MessageHandler for CommitFault<'_> {
            fn on_accepted(&mut self, _: &DispatchMessage) -> Result<(), String> {
                if self.calls.fetch_add(1, Ordering::SeqCst) == 0 {
                    self.cluster.request_errors(
                        RDKafkaApiKey::OffsetCommit,
                        &[RDKafkaRespErr::RD_KAFKA_RESP_ERR_GROUP_AUTHORIZATION_FAILED],
                    );
                }
                Ok(())
            }
        }

        let cluster = MockCluster::new(1).unwrap();
        let topic = "batch.task.dispatch.import.node.worker-1";
        cluster.create_topic(topic, 1, 1).unwrap();
        let cfg = test_config(cluster.bootstrap_servers());
        let calls = Arc::new(AtomicUsize::new(0));
        let handler = CommitFault {
            cluster: &cluster,
            calls: calls.clone(),
        };
        let base: BaseConsumer = cfg.to_client_config().create().unwrap();
        let mut consumer = KafkaTaskConsumer::with_consumer(cfg, base, handler).unwrap();
        consumer.consumer.unsubscribe();
        let mut assignment = TopicPartitionList::new();
        assignment
            .add_partition_offset(topic, 0, Offset::Beginning)
            .unwrap();
        consumer.consumer.assign(&assignment).unwrap();
        let producer: BaseProducer = ClientConfig::new()
            .set("bootstrap.servers", cluster.bootstrap_servers())
            .create()
            .unwrap();
        producer
            .send(
                BaseRecord::<(), str>::to(topic)
                    .partition(0)
                    .payload(r#"{"taskId":5,"tenantId":"tenant-a","schemaVersion":"v1"}"#),
            )
            .unwrap();
        producer.flush(Duration::from_secs(5)).unwrap();
        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            assert!(Instant::now() < deadline, "未触发提交失败");
            consumer.poll_once(&|| 0).expect("提交失败应暂扣当前分区");
            if consumer
                .withheld_ceilings
                .contains_key(&(topic.to_string(), 0))
            {
                break;
            }
        }
        assert_eq!(
            consumer.withheld_ceilings.get(&(topic.to_string(), 0)),
            Some(&0)
        );
        cluster.clear_request_errors(RDKafkaApiKey::OffsetCommit);
        while calls.load(Ordering::SeqCst) < 2 {
            assert!(Instant::now() < deadline, "提交失败后未重试原消息");
            consumer.poll_once(&|| 0).unwrap();
        }
        let mut requested = TopicPartitionList::new();
        requested.add_partition(topic, 0);
        let committed = consumer
            .consumer
            .committed_offsets(requested, Duration::from_secs(5))
            .unwrap();
        assert_eq!(
            committed.find_partition(topic, 0).unwrap().offset(),
            Offset::Offset(1)
        );
    }

    #[test]
    fn controlled_loop_continues_other_partition_after_offset_query_failure() {
        use rdkafka::mocking::MockCluster;
        use rdkafka::producer::{BaseProducer, BaseRecord, Producer};
        use rdkafka::types::{RDKafkaApiKey, RDKafkaRespErr};
        use std::sync::atomic::AtomicBool;
        use std::time::Instant;

        let cluster = MockCluster::new(1).unwrap();
        let topic = "batch.task.dispatch.import.node.worker-1";
        cluster.create_topic(topic, 2, 1).unwrap();
        let cfg = test_config(cluster.bootstrap_servers());
        let accepted = Arc::new(std::sync::Mutex::new(Vec::new()));
        let handler = CollectingHandler {
            accepted: accepted.clone(),
        };
        let base: BaseConsumer = cfg.to_client_config().create().unwrap();
        let mut consumer = KafkaTaskConsumer::with_consumer(cfg, base, handler).unwrap();
        consumer.consumer.unsubscribe();
        let mut assignment = TopicPartitionList::new();
        assignment
            .add_partition_offset(topic, 0, Offset::Beginning)
            .unwrap();
        assignment
            .add_partition_offset(topic, 1, Offset::Beginning)
            .unwrap();
        consumer.consumer.assign(&assignment).unwrap();
        let producer: BaseProducer = ClientConfig::new()
            .set("bootstrap.servers", cluster.bootstrap_servers())
            .create()
            .unwrap();
        producer
            .send(
                BaseRecord::<(), str>::to(topic)
                    .partition(0)
                    .payload(r#"{"taskId":1,"tenantId":"tenant-a","schemaVersion":"v3"}"#),
            )
            .unwrap();
        producer.flush(Duration::from_secs(5)).unwrap();
        cluster.request_errors(
            RDKafkaApiKey::OffsetFetch,
            &[RDKafkaRespErr::RD_KAFKA_RESP_ERR_GROUP_AUTHORIZATION_FAILED],
        );
        let running = Arc::new(AtomicBool::new(true));
        let signal = running.clone();
        let (results, receiver) = std::sync::mpsc::channel();
        let thread = std::thread::spawn(move || {
            let result = consumer.run_controlled(|| 0, || false, || signal.load(Ordering::SeqCst));
            let _ = results.send(result);
        });
        let early = receiver.recv_timeout(Duration::from_secs(2));
        if early.is_ok() {
            running.store(false, Ordering::SeqCst);
            thread.join().unwrap();
            panic!("一个分区位点查询失败时消费循环不应退出: {early:?}");
        }
        cluster.clear_request_errors(RDKafkaApiKey::OffsetFetch);
        producer
            .send(
                BaseRecord::<(), str>::to(topic)
                    .partition(1)
                    .payload(r#"{"taskId":2,"tenantId":"tenant-a","schemaVersion":"v1"}"#),
            )
            .unwrap();
        producer.flush(Duration::from_secs(5)).unwrap();
        let deadline = Instant::now() + Duration::from_secs(8);
        while !accepted.lock().unwrap().contains(&"2".to_string()) && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(50));
        }
        running.store(false, Ordering::SeqCst);
        let result = receiver
            .recv_timeout(Duration::from_secs(5))
            .expect("消费者未停止");
        thread.join().unwrap();
        assert!(result.is_ok(), "消费循环失败: {result:?}");
        assert!(
            accepted.lock().unwrap().contains(&"2".to_string()),
            "其他分区未继续消费"
        );
    }

    #[test]
    fn withheld_partition_does_not_consume_following_records() {
        use rdkafka::mocking::MockCluster;
        use rdkafka::producer::{BaseProducer, BaseRecord, Producer};
        use std::time::Instant;

        let cluster = MockCluster::new(1).expect("mock cluster");
        let topic = "batch.task.dispatch.import.node.worker-1";
        cluster.create_topic(topic, 2, 1).expect("topic");
        let producer: BaseProducer = ClientConfig::new()
            .set("bootstrap.servers", cluster.bootstrap_servers())
            .create()
            .expect("producer");
        let send = |partition, payload: &str| {
            producer
                .send(
                    BaseRecord::<(), str>::to(topic)
                        .partition(partition)
                        .payload(payload),
                )
                .expect("send");
            producer.flush(Duration::from_secs(5)).expect("flush");
        };
        send(
            0,
            r#"{"taskId":1,"tenantId":"tenant-a","schemaVersion":"v3"}"#,
        );

        let cfg = test_config(cluster.bootstrap_servers());
        let accepted = Arc::new(std::sync::Mutex::new(Vec::new()));
        let handler = CollectingHandler {
            accepted: accepted.clone(),
        };
        let base: BaseConsumer = cfg.to_client_config().create().expect("consumer");
        let mut consumer = KafkaTaskConsumer::with_consumer(cfg, base, handler).expect("sdk");
        consumer.consumer.unsubscribe();
        let mut assignment = TopicPartitionList::new();
        assignment
            .add_partition_offset(topic, 0, Offset::Beginning)
            .unwrap();
        assignment
            .add_partition_offset(topic, 1, Offset::Beginning)
            .unwrap();
        consumer.consumer.assign(&assignment).expect("assign");
        let deadline = Instant::now() + Duration::from_secs(8);
        loop {
            assert!(Instant::now() < deadline, "未收到拒绝消息");
            if matches!(
                consumer.poll_once(&|| 0).expect("poll"),
                Some(RecordDisposition::RejectedSchema)
            ) {
                break;
            }
        }
        let mut first_position = TopicPartitionList::new();
        first_position.add_partition(topic, 0);
        let committed = consumer
            .consumer
            .committed_offsets(first_position, Duration::from_secs(5))
            .expect("读取初始暂扣位置");
        assert_eq!(
            committed.find_partition(topic, 0).unwrap().offset(),
            Offset::Offset(0)
        );
        send(
            0,
            r#"{"taskId":2,"tenantId":"tenant-a","schemaVersion":"v1"}"#,
        );
        send(
            1,
            r#"{"taskId":3,"tenantId":"tenant-a","schemaVersion":"v1"}"#,
        );
        let deadline = Instant::now() + Duration::from_millis(1300);
        while Instant::now() < deadline {
            consumer.poll_once(&|| 0).expect("poll");
        }
        let handled = accepted.lock().unwrap();
        assert!(
            handled.contains(&"3".to_string()),
            "其他分区应继续处理: {handled:?}"
        );
        assert!(
            !handled.contains(&"2".to_string()),
            "未确认分区不应处理后续消息: {handled:?}"
        );
        drop(handled);

        // 提交位置查询失败时不可处理或提交高于原暂扣位置的消息。
        use rdkafka::types::{RDKafkaApiKey, RDKafkaRespErr};
        cluster.request_errors(
            RDKafkaApiKey::OffsetFetch,
            &[RDKafkaRespErr::RD_KAFKA_RESP_ERR_GROUP_AUTHORIZATION_FAILED],
        );
        consumer
            .consumer
            .seek(topic, 0, Offset::Offset(1), Duration::from_secs(5))
            .expect("定位高位消息");
        let mut partition_zero = TopicPartitionList::new();
        partition_zero.add_partition(topic, 0);
        let previous_retry = consumer.withheld_retries[&(topic.to_string(), 0)];
        consumer.consumer.resume(&partition_zero).expect("恢复分区");
        let deadline = Instant::now() + Duration::from_secs(8);
        loop {
            assert!(Instant::now() < deadline, "未触发提交位置查询失败");
            consumer
                .poll_once(&|| 0)
                .expect("位点查询失败应暂扣当前分区");
            if consumer
                .withheld_retries
                .get(&(topic.to_string(), 0))
                .is_some_and(|deadline| *deadline > previous_retry)
            {
                break;
            }
        }
        assert!(!accepted.lock().unwrap().contains(&"2".to_string()));
        assert_eq!(
            consumer.withheld_ceilings.get(&(topic.to_string(), 0)),
            Some(&0)
        );
        cluster.clear_request_errors(RDKafkaApiKey::OffsetFetch);
        // 查询失败后不能只保留内存上限：已经拉取的高位消息使游标前移，
        // 无新消息时也必须回退并重新收到暂扣记录。
        let deadline = Instant::now() + Duration::from_secs(6);
        loop {
            assert!(Instant::now() < deadline, "查询失败后未重读原暂扣消息");
            if matches!(
                consumer.poll_once(&|| 0).expect("重读暂扣记录"),
                Some(RecordDisposition::RejectedSchema)
            ) {
                break;
            }
        }

        // 模拟分区转交：另一成员已处理暂扣记录并提交下一位置，旧上限
        // 不得阻止当前实例重新取得该分区后的提交。
        let other: BaseConsumer = consumer.config.to_client_config().create().expect("other");
        let mut transferred = TopicPartitionList::new();
        transferred
            .add_partition_offset(topic, 0, Offset::Offset(1))
            .unwrap();
        other
            .commit(&transferred, CommitMode::Sync)
            .expect("转交成员提交");
        consumer
            .consumer
            .seek(topic, 0, Offset::Offset(1), Duration::from_secs(5))
            .expect("新分配位置");
        consumer.consumer.resume(&transferred).expect("恢复分区");
        let deadline = Instant::now() + Duration::from_secs(8);
        while !accepted.lock().unwrap().contains(&"2".to_string()) {
            assert!(Instant::now() < deadline, "转交后未恢复处理");
            consumer.poll_once(&|| 0).expect("poll");
        }
        let mut requested = TopicPartitionList::new();
        requested.add_partition(topic, 0);
        let committed = consumer
            .consumer
            .committed_offsets(requested, Duration::from_secs(5))
            .expect("读取提交位置");
        assert_eq!(
            committed.find_partition(topic, 0).unwrap().offset(),
            Offset::Offset(2)
        );
    }

    fn assigned_topics(tpl: &TopicPartitionList) -> Vec<String> {
        tpl.elements()
            .into_iter()
            .map(|elem| elem.topic().to_string())
            .collect()
    }

    fn send_record(
        producer: &rdkafka::producer::FutureProducer,
        topic: &str,
        partition: i32,
        payload: &[u8],
    ) {
        let delivery = futures_executor::block_on(
            producer.send(
                rdkafka::producer::FutureRecord::<(), [u8]>::to(topic)
                    .partition(partition)
                    .payload(payload),
                Duration::from_secs(10),
            ),
        );
        if let Err((err, _message)) = delivery {
            panic!("produce to {topic} failed: {err:?}");
        }
    }

    /// 真实消费组的成员转交和交回不得留下旧提交屏障。
    #[test]
    fn real_broker_two_member_partition_transfer() {
        let bootstrap = match std::env::var("BATCH_SDK_KAFKA_BOOTSTRAP") {
            Ok(value) if !value.trim().is_empty() => value,
            _ => return,
        };
        use rdkafka::admin::{AdminClient, AdminOptions, NewTopic, TopicReplication};
        use rdkafka::client::DefaultClientContext;
        use rdkafka::producer::FutureProducer;
        use std::collections::HashSet;
        use std::sync::Mutex;
        use std::time::Instant;

        struct TransferHandler {
            reject_initial: bool,
            seen: Arc<Mutex<HashSet<i64>>>,
        }
        impl MessageHandler for TransferHandler {
            fn on_accepted(&mut self, message: &DispatchMessage) -> Result<(), String> {
                self.seen.lock().unwrap().insert(message.task_id);
                if self.reject_initial && (100..104).contains(&message.task_id) {
                    Err("模拟初次认领失败".into())
                } else {
                    Ok(())
                }
            }
        }

        let mut cfg = test_config(bootstrap.clone());
        cfg.poll_interval = Duration::from_millis(100);
        cfg.worker_code = format!(
            "worker-transfer-{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        );
        let topic = format!("batch.task.dispatch.import.node.{}", cfg.worker_code);
        let admin: AdminClient<DefaultClientContext> = ClientConfig::new()
            .set("bootstrap.servers", &bootstrap)
            .create()
            .expect("创建管理客户端");
        let result = futures_executor::block_on(admin.create_topics(
            &[NewTopic::new(&topic, 4, TopicReplication::Fixed(1))],
            &AdminOptions::new().operation_timeout(Some(Duration::from_secs(15))),
        ))
        .expect("创建主题请求");
        assert!(result.into_iter().all(|item| item.is_ok()), "创建主题失败");
        let producer: FutureProducer = ClientConfig::new()
            .set("bootstrap.servers", &bootstrap)
            .set("enable.idempotence", "true")
            .create()
            .expect("创建生产者");

        // 在消费组建立前发布，验证默认最早位置不会漏掉已有任务。
        for partition in 0..4 {
            let message = format!(
                "{{\"taskId\":{},\"tenantId\":\"tenant-a\",\"schemaVersion\":\"v1\",\"workerType\":\"import\"}}",
                100 + partition
            );
            send_record(&producer, &topic, partition, message.as_bytes());
        }
        let original_seen = Arc::new(Mutex::new(HashSet::new()));
        // 使用生产默认位置验证新消费组的真实转交。
        let base_config = cfg.to_client_config();
        let original_base: BaseConsumer = base_config.create().expect("创建原成员 consumer");
        let mut original = KafkaTaskConsumer::with_consumer(
            cfg.clone(),
            original_base,
            TransferHandler {
                reject_initial: true,
                seen: original_seen.clone(),
            },
        )
        .expect("创建原成员");
        let deadline = Instant::now() + Duration::from_secs(30);
        while original
            .assignment()
            .unwrap()
            .elements_for_topic(&topic)
            .len()
            != 4
        {
            assert!(Instant::now() < deadline, "原成员未获得四个分区");
            original.poll_once(&|| 0).expect("等待首次分配");
        }
        let deadline = Instant::now() + Duration::from_secs(30);
        while original_seen.lock().unwrap().len() != 4 {
            assert!(
                Instant::now() < deadline,
                "原成员未暂扣各分区任务: {:?}",
                original_seen.lock().unwrap()
            );
            original.poll_once(&|| 0).expect("处理暂扣任务");
        }

        let peer_seen = Arc::new(Mutex::new(HashSet::new()));
        let peer_base: BaseConsumer = base_config.create().expect("创建接管成员 consumer");
        let mut peer = KafkaTaskConsumer::with_consumer(
            cfg.clone(),
            peer_base,
            TransferHandler {
                reject_initial: false,
                seen: peer_seen.clone(),
            },
        )
        .expect("创建接管成员");
        let deadline = Instant::now() + Duration::from_secs(45);
        while peer_seen.lock().unwrap().is_empty() {
            assert!(Instant::now() < deadline, "另一成员未取得并处理暂扣任务");
            original.poll_once(&|| 0).expect("原成员处理转交");
            peer.poll_once(&|| 0).expect("接管成员处理转交");
        }
        let peer_task = *peer_seen.lock().unwrap().iter().next().unwrap();
        let partition = i32::try_from(peer_task - 100).unwrap();
        assert!(
            original_seen.lock().unwrap().contains(&peer_task),
            "接管成员应处理原成员曾暂扣的任务"
        );
        let mut target = TopicPartitionList::new();
        target.add_partition(&topic, partition);
        let positions = peer
            .consumer
            .committed_offsets(target, Duration::from_secs(5))
            .expect("查询接管后的提交位置");
        assert_eq!(
            positions
                .find_partition(&topic, partition)
                .unwrap()
                .offset(),
            Offset::Offset(1),
            "接管任务应恰好提交到下一位置"
        );
        // 原成员至少处理一次已撤销分区的分配状态，再让接管成员退出。
        let deadline = Instant::now() + Duration::from_secs(15);
        while original
            .assignment()
            .unwrap()
            .find_partition(&topic, partition)
            .is_some()
        {
            assert!(Instant::now() < deadline, "原成员仍持有已转交分区");
            original.poll_once(&|| 0).expect("等待分区撤销");
        }
        original.poll_once(&|| 0).expect("清理旧分区状态");
        peer.consumer.unsubscribe();
        drop(peer);

        let deadline = Instant::now() + Duration::from_secs(45);
        while original
            .assignment()
            .unwrap()
            .find_partition(&topic, partition)
            .is_none()
        {
            assert!(Instant::now() < deadline, "原成员未重新取得分区");
            original.poll_once(&|| 0).expect("等待分区交回");
        }
        let next_task = 200 + partition;
        let message = format!(
            "{{\"taskId\":{next_task},\"tenantId\":\"tenant-a\",\"schemaVersion\":\"v1\",\"workerType\":\"import\"}}"
        );
        send_record(&producer, &topic, partition, message.as_bytes());
        let deadline = Instant::now() + Duration::from_secs(30);
        while !original_seen
            .lock()
            .unwrap()
            .contains(&i64::from(next_task))
        {
            assert!(Instant::now() < deadline, "交回后原成员未处理新任务");
            original.poll_once(&|| 0).expect("处理交回分区的新任务");
        }
        let mut target = TopicPartitionList::new();
        target.add_partition(&topic, partition);
        let positions = original
            .consumer
            .committed_offsets(target, Duration::from_secs(5))
            .expect("查询交回后的提交位置");
        assert_eq!(
            positions
                .find_partition(&topic, partition)
                .unwrap()
                .offset(),
            Offset::Offset(2),
            "交回后的新任务应提交到下一位置"
        );
    }

    #[test]
    fn end_to_end_consume_against_real_broker() {
        // Env gate: only runs in CI with a broker (mirrors the TS/Go/Python
        // integration gate). No broker → return green.
        let bootstrap = match std::env::var("BATCH_SDK_KAFKA_BOOTSTRAP") {
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
        let new_topic = NewTopic::new(&topic, 2, TopicReplication::Fixed(1));
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
        // 后续派单在分区完成分配后发送；新消费组默认从最早位置读取。
        let producer: FutureProducer = ClientConfig::new()
            .set("bootstrap.servers", &bootstrap)
            .create()
            .expect("producer");
        send_record(
            &producer,
            &topic,
            0,
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
            if consumer
                .assignment()
                .expect("assignment")
                .elements_for_topic(&topic)
                .len()
                == 2
            {
                break;
            }
        }
        let assignment = consumer.assignment().expect("assignment");
        assert!(
            assignment.elements_for_topic(&topic).len() == 2,
            "consumer must be assigned to {topic} before producing live records; assigned={:?}",
            assigned_topics(&assignment)
        );

        // 分区 0 暂扣外租户消息后不得处理后续任务，分区 1 仍须继续。
        let good1 =
            br#"{"taskId":1,"tenantId":"tenant-a","schemaVersion":"v1","workerType":"import"}"#;
        let foreign =
            br#"{"taskId":2,"tenantId":"tenant-b","schemaVersion":"v1","workerType":"import"}"#;
        let good3 =
            br#"{"taskId":3,"tenantId":"tenant-a","schemaVersion":"v1","workerType":"import"}"#;
        let good4 =
            br#"{"taskId":4,"tenantId":"tenant-a","schemaVersion":"v1","workerType":"import"}"#;
        for payload in [good1.as_slice(), foreign.as_slice(), good3.as_slice()] {
            send_record(&producer, &topic, 0, payload);
        }
        send_record(&producer, &topic, 1, good4);

        // Poll for up to ~15s or until all decisions have been observed.
        let deadline = std::time::Instant::now() + Duration::from_secs(15);
        let mut dispositions = Vec::<String>::new();
        let mut saw_good1 = false;
        let mut saw_good4 = false;
        let mut foreign_count = 0;
        while std::time::Instant::now() < deadline {
            let disp = consumer.poll_once(&if_read).expect("poll");
            dispositions.push(format!("{disp:?}"));
            match disp {
                Some(RecordDisposition::Accepted) => {
                    let acc = accepted.lock().unwrap();
                    saw_good1 = acc.iter().any(|t| t == "1");
                    saw_good4 = acc.iter().any(|t| t == "4");
                }
                Some(RecordDisposition::DroppedForeignTenant) => foreign_count += 1,
                _ => {}
            }
            if saw_good1 && saw_good4 && foreign_count >= 2 {
                break;
            }
        }

        let got = accepted.lock().unwrap().clone();
        assert!(
            saw_good1 && got.contains(&"1".to_string()),
            "good record 1 must be accepted; got={got:?}; dispositions={dispositions:?}"
        );
        assert!(
            foreign_count >= 2,
            "外租户消息应在原分区重复投递；dispositions={dispositions:?}"
        );
        assert!(
            saw_good4 && got.contains(&"4".to_string()),
            "其他分区应继续处理；got={got:?}; dispositions={dispositions:?}"
        );
        assert!(
            !got.contains(&"3".to_string()),
            "被暂扣分区不得处理后续消息；got={got:?}; dispositions={dispositions:?}"
        );
        assert!(
            !got.contains(&"2".to_string()),
            "foreign-tenant record must be dropped (§1.9)"
        );
        let mut positions = TopicPartitionList::new();
        positions.add_partition(&topic, 0);
        positions.add_partition(&topic, 1);
        let committed = consumer
            .consumer
            .committed_offsets(positions, Duration::from_secs(5))
            .expect("查询提交位点");
        assert_eq!(
            committed.find_partition(&topic, 0).unwrap().offset(),
            Offset::Offset(2)
        );
        assert_eq!(
            committed.find_partition(&topic, 1).unwrap().offset(),
            Offset::Offset(1)
        );
    }
}
