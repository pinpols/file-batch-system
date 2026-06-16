//! Wire-protocol types for the orchestrator <-> SDK control plane.
//!
//! Mirrors `docs/sdk/wire-protocol.md` §2 (reverse directives) and §B (error
//! codes). `Option` fields distinguish "absent/null" from a zero value (e.g.
//! `should_drain = Some(false)` vs `should_drain = None`).

/// A `nextHeartbeatHint`: an ISO-8601 duration string ("PT15S"), a raw number of
/// seconds, or null/absent. Normalized to ms in [`crate::decide`].
#[derive(Debug, Clone, PartialEq)]
pub enum HeartbeatHint {
    /// ISO-8601 duration string, e.g. "PT15S".
    Iso(String),
    /// Raw seconds (JSON numbers decode here).
    Seconds(f64),
}

/// Heartbeat reverse-directive response body (wire-protocol §2.1).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct HeartbeatResponse {
    pub platform_status: Option<String>,
    pub should_drain: Option<bool>,
    pub desired_max_concurrent: Option<i64>,
    pub paused_task_types: Vec<String>,
    pub next_heartbeat_hint: Option<HeartbeatHint>,
}

/// Lease-renew response body (wire-protocol §2.2).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct RenewResponse {
    pub lease_until: Option<String>,
    pub cancel_requested: Option<bool>,
}

/// Canonical platform error codes (wire-protocol §B report `errorCode`).
pub mod error_code {
    pub const SUCCESS: &str = "SUCCESS";
    pub const TIMEOUT: &str = "TIMEOUT";
    pub const CANCELLED: &str = "CANCELLED";
    pub const KILLED: &str = "KILLED";
    pub const SECURITY_REJECTED: &str = "SECURITY_REJECTED";
    pub const EXECUTION_FAILED: &str = "EXECUTION_FAILED";
    pub const CONFIG_INVALID: &str = "CONFIG_INVALID";
    pub const RESOURCE_EXHAUSTED: &str = "RESOURCE_EXHAUSTED";
}

/// ADR-012 failure classes.
pub mod failure_class {
    pub const TRANSIENT: &str = "TRANSIENT";
    pub const TERMINAL_USER: &str = "TERMINAL_USER";
    pub const TERMINAL_CONFIG: &str = "TERMINAL_CONFIG";
    pub const BUSINESS: &str = "BUSINESS";
}
