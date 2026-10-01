//! BYO worker SDK — pure decision core + shared constants (Rust reference impl).
//!
//! This crate mirrors the TypeScript (`../typescript`) and Go
//! (`../go`) reference implementations: same decision behavior,
//! idiomatic Rust；默认构建仅直接引入轻量系统随机依赖,用于幂等键和签名 nonce。
//!
//! * [`constants`] — the 4 cross-language shared constant arrays (parity-guarded
//!   against `docs/api/sdk-shared-constants.yaml`).
//! * [`protocol`] — wire-protocol request/response types + error-code consts.
//! * [`decide`] — the pure decision functions and the unified [`decide::Decision`].

pub mod client;
pub mod constants;
pub mod decide;
pub mod protocol;
mod random;

/// Phase 3 — the real Kafka consumer adapter (rdkafka), behind the optional
/// `kafka` feature. The default build excludes this module entirely, so the
/// crate avoids the Kafka dependency unless `--features kafka` is passed.
#[cfg(feature = "kafka")]
pub mod kafka;

// Re-exports for ergonomic top-level use, mirroring the TS `index.ts` surface.
pub use constants::{
    DRY_RUN_SAFE_CAPABILITY, SENSITIVE_KEYWORDS, SUPPORTED_SCHEMA_VERSIONS, TASK_STATUSES,
    WORKER_RUNTIME_STATES,
};
pub use decide::{
    apply_heartbeat_directive, apply_renew, classify_http, classify_schema_version,
    decide_backpressure, decide_register, exponential_backoff, parse_iso8601_duration_ms,
    plan_stop, Decision, CLIENT_ERROR_FAIL_FAST_THRESHOLD, DEFAULT_RETRY_BASE_MS,
    DEFAULT_RETRY_MAX_ATTEMPTS, MIN_HEARTBEAT_INTERVAL_MS,
};
pub use protocol::{HeartbeatHint, HeartbeatResponse, RenewResponse};
