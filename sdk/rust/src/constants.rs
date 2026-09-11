//! Cross-language shared constants (Lane P drift guard, ADR-035).
//!
//! These arrays are mirrored from `docs/api/sdk-shared-constants.yaml` and are
//! NOT re-authored freely: `tests/constants_parity.rs` deep-equals each array
//! against the YAML source of truth and fails on any drift (contract §1.1).

/// `schema_versions_supported`: known major versions the SDK accepts
/// (wire-protocol §A).
pub const SUPPORTED_SCHEMA_VERSIONS: &[&str] = &["v1", "v2"];

/// `worker_runtime_states`: worker FSM states.
pub const WORKER_RUNTIME_STATES: &[&str] = &["NORMAL", "DEGRADED", "PAUSED", "DRAINING"];

/// `sensitive_keywords`: credential-leak detection keywords.
pub const SENSITIVE_KEYWORDS: &[&str] = &[
    "password",
    "passwd",
    "secret",
    "apikey",
    "api_key",
    "token",
    "credential",
    "accesskey",
    "access_key",
    "privatekey",
    "private_key",
    "clientsecret",
    "client_secret",
];

/// `task_statuses`: terminal + lifecycle task statuses.
pub const TASK_STATUSES: &[&str] = &[
    "CREATED",
    "READY",
    "RUNNING",
    "SUCCESS",
    "FAILED",
    "CANCELLED",
    "TERMINATED",
];
