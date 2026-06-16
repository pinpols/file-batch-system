//! BYO worker SDK — pure decision core + shared constants (Rust reference impl).
//!
//! This crate mirrors the TypeScript (`batch-worker-sdk-typescript`) and Go
//! (`batch-worker-sdk-go`) reference implementations: same decision behavior,
//! idiomatic Rust, **zero external dependencies** (std-only).
//!
//! * [`constants`] — the 4 cross-language shared constant arrays (parity-guarded
//!   against `docs/api/sdk-shared-constants.yaml`).
//! * [`protocol`] — wire-protocol request/response types + error-code consts.
//! * [`decide`] — the pure decision functions and the unified [`decide::Decision`].

pub mod client;
pub mod constants;
pub mod decide;
pub mod protocol;

/// Phase 3 — the real Kafka consumer adapter (rdkafka), behind the optional
/// `kafka` feature. The default build excludes this module entirely, so the
/// crate stays std-only with zero external dependencies unless `--features
/// kafka` is passed.
#[cfg(feature = "kafka")]
pub mod kafka;

// Re-exports for ergonomic top-level use, mirroring the TS `index.ts` surface.
pub use constants::{
    SENSITIVE_KEYWORDS, SUPPORTED_SCHEMA_VERSIONS, TASK_STATUSES, WORKER_RUNTIME_STATES,
};
pub use decide::{
    apply_heartbeat_directive, apply_renew, classify_http, classify_schema_version,
    decide_backpressure, decide_register, exponential_backoff, parse_iso8601_duration_ms,
    plan_stop, Decision, CLIENT_ERROR_FAIL_FAST_THRESHOLD, DEFAULT_RETRY_BASE_MS,
    DEFAULT_RETRY_MAX_ATTEMPTS, MIN_HEARTBEAT_INTERVAL_MS,
};
pub use protocol::{HeartbeatHint, HeartbeatResponse, RenewResponse};
