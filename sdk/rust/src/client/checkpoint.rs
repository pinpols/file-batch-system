//! ADR-037 P1–P3 — shard-level checkpoint / resume + reliable-commit primitives.
//!
//! This is the **thin BYO** surface: the Rust SDK ships no typed templates, so
//! instead of an `SdkAbstractParallelStreamHandler` it exposes the underlying
//! *primitives* on [`TaskContext`](super::handler::TaskContext) for a tenant
//! handler to drive directly. The three decisions of ADR-037 map as:
//!
//! * **决策一 (P1)** — [`SdkCheckpoint`] trait + [`SdkCheckpointState`]:
//!   load the break-position / counters at `execute` start, skip when
//!   `completed`, resume from `break_position` (data-key coordinates, *not* an
//!   offset). Persistence is the tenant's job; [`InMemoryCheckpoint`] is a
//!   reference impl for tests/dev.
//! * **决策二 (P2)** — `ctx.commit(break_position)`: one call that saves the
//!   checkpoint **and** (rate-limited) reports progress. See
//!   [`TaskContext::commit`](super::handler::TaskContext::commit).
//! * **决策三 (P3)** — cooperative cancel: after a successful `commit`, if
//!   cancellation was requested the call returns [`SdkTaskStopped`] carrying the
//!   already-committed safe-point. The run path / testkit map that to a
//!   **cancelled** terminal (not a failure).
//!
//! ## Same-transaction requirement (强约束)
//!
//! ADR-037 决策二 makes the atomicity of "commit business data" + "save
//! checkpoint" the *root* of resume correctness: if the business write commits
//! but the checkpoint does not (or vice-versa), a re-dispatch either reprocesses
//! or loses rows. A real [`SdkCheckpoint`] backed by a tenant control table
//! **must** persist the [`SdkCheckpointState`] inside the *same* transaction as
//! the business batch it describes — e.g. update the checkpoint row on the same
//! JDBC `Connection`, then a single `connection.commit()`. The SDK cannot
//! enforce this; tenants own it and code review gates it. [`InMemoryCheckpoint`]
//! is deliberately *not* transactional and is for tests/dev only.
//!
//! ## JSON value type
//!
//! `break_position` values are arbitrary JSON (a primary key, a range tuple, a
//! sort key). To keep the default build **zero-dependency** (the crate ships no
//! `serde`/`serde_json` unless a feature pulls them) the value type is the
//! crate-owned [`JsonValue`]. Its `Serialize`/`Deserialize` impls are derived
//! (and serialize as plain JSON, indistinguishable from `serde_json::Value`)
//! whenever the `serde` feature is active — which the `http` and `kafka`
//! features both enable transitively.

use std::collections::{BTreeMap, HashMap};
use std::fmt;
use std::sync::{Arc, Mutex};

/// An arbitrary JSON value used for break-position coordinates.
///
/// Mirrors the Java contract's `Object` / TS `unknown` value slot. Kept
/// crate-local so the default build stays std-only; under the `serde` feature
/// it derives `Serialize`/`Deserialize` and round-trips as ordinary JSON, so a
/// `BTreeMap<String, JsonValue>` is wire-compatible with the Java
/// `Map<String, Object>` break position.
#[derive(Debug, Clone, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
#[cfg_attr(feature = "serde", serde(untagged))]
pub enum JsonValue {
    /// JSON `null`.
    Null,
    /// JSON boolean.
    Bool(bool),
    /// JSON integer — kept distinct so primary keys round-trip without `.0`.
    /// Declared before [`JsonValue::Num`] so untagged deserialization prefers an
    /// exact `i64` for integral numbers (serde tries variants top-down).
    Int(i64),
    /// JSON number (stored as `f64`; integer keys fit exactly up to 2^53).
    Num(f64),
    /// JSON string.
    Str(String),
    /// JSON array.
    Arr(Vec<JsonValue>),
    /// JSON object (ordered for deterministic serialization).
    Obj(BTreeMap<String, JsonValue>),
}

impl JsonValue {
    /// Borrow as `&str` if this is a string value.
    pub fn as_str(&self) -> Option<&str> {
        match self {
            JsonValue::Str(s) => Some(s),
            _ => None,
        }
    }

    /// Read as `i64` if this is an integer (or an exactly-integral number).
    pub fn as_i64(&self) -> Option<i64> {
        match self {
            JsonValue::Int(n) => Some(*n),
            JsonValue::Num(f) if f.fract() == 0.0 => Some(*f as i64),
            _ => None,
        }
    }
}

impl From<&str> for JsonValue {
    fn from(s: &str) -> Self {
        JsonValue::Str(s.to_string())
    }
}
impl From<String> for JsonValue {
    fn from(s: String) -> Self {
        JsonValue::Str(s)
    }
}
impl From<i64> for JsonValue {
    fn from(n: i64) -> Self {
        JsonValue::Int(n)
    }
}
impl From<bool> for JsonValue {
    fn from(b: bool) -> Self {
        JsonValue::Bool(b)
    }
}

/// A break-position map: business-key coordinates of "how far processed".
pub type BreakPosition = BTreeMap<String, JsonValue>;

/// The persisted resume state for one task (ADR-037 决策一 `SdkCheckpointState`).
///
/// `break_position` is the data-key (primary key / range key) the handler has
/// processed up to — **not** a Kafka offset. `succeed_count` / `fail_count` are
/// restored on resume so progress does not reset to zero. `completed` makes
/// resume idempotent: a re-dispatch of an already-finished task short-circuits.
#[derive(Debug, Clone, PartialEq, Default)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct SdkCheckpointState {
    /// How far processed, in business-key coordinates.
    #[cfg_attr(feature = "serde", serde(default, rename = "breakPosition"))]
    pub break_position: BreakPosition,
    /// Records committed successfully so far.
    #[cfg_attr(feature = "serde", serde(default, rename = "succeedCount"))]
    pub succeed_count: i64,
    /// Records that failed so far.
    #[cfg_attr(feature = "serde", serde(default, rename = "failCount"))]
    pub fail_count: i64,
    /// Whether the task has fully completed (resume short-circuits when `true`).
    #[cfg_attr(feature = "serde", serde(default))]
    pub completed: bool,
}

impl SdkCheckpointState {
    /// A fresh "nothing processed yet" state.
    pub fn empty() -> Self {
        Self::default()
    }
}

/// Errors a [`SdkCheckpoint`] implementation may surface from `load` / `save`.
///
/// The default in-memory impl never fails, but real (DB / KV / object-store)
/// backings can — they return [`CheckpointError`] rather than panicking so the
/// handler can decide (retry / fail the task) on an external-IO error. Never
/// `unwrap` these on the handler side.
#[derive(Debug)]
pub enum CheckpointError {
    /// The backing store rejected or could not service the operation.
    Backend(String),
}

impl fmt::Display for CheckpointError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            CheckpointError::Backend(msg) => write!(f, "checkpoint backend error: {msg}"),
        }
    }
}

impl std::error::Error for CheckpointError {}

/// ADR-037 决策一 — the checkpoint persistence SPI.
///
/// Two actions, no policy about *where* state lives (tenant control table, KV,
/// object store). Implementations **must** honor the same-transaction
/// requirement documented at the module level: [`save`](SdkCheckpoint::save)
/// has to be atomic with the business batch it describes. `Send + Sync` so the
/// context can hold it behind an `Arc` and share it across the std::thread
/// worker model.
pub trait SdkCheckpoint: Send + Sync {
    /// Read back the last checkpoint for `task_id`; `Ok(None)` on first run.
    fn load(&self, task_id: &str) -> Result<Option<SdkCheckpointState>, CheckpointError>;

    /// Persist `state` for `task_id`.
    ///
    /// **强约束**: a real implementation must write this inside the *same*
    /// transaction as the business data the `state.break_position` describes, so
    /// the two can never tear apart across a crash. See the module docs.
    fn save(&self, task_id: &str, state: &SdkCheckpointState) -> Result<(), CheckpointError>;
}

/// In-memory reference [`SdkCheckpoint`] (`Arc<Mutex<HashMap<..>>>`).
///
/// For tests and local development only. It is **not** transactional and does
/// not survive process restart, so it provides none of the crash-safety the
/// real same-transaction contract is about — a real tenant impl backed by their
/// business store is required in production.
#[derive(Clone, Default)]
pub struct InMemoryCheckpoint {
    store: Arc<Mutex<HashMap<String, SdkCheckpointState>>>,
}

impl InMemoryCheckpoint {
    /// A fresh, empty in-memory checkpoint store.
    pub fn new() -> Self {
        Self::default()
    }
}

impl fmt::Debug for InMemoryCheckpoint {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("InMemoryCheckpoint").finish_non_exhaustive()
    }
}

impl SdkCheckpoint for InMemoryCheckpoint {
    fn load(&self, task_id: &str) -> Result<Option<SdkCheckpointState>, CheckpointError> {
        // A poisoned lock is an internal invariant break, not external input;
        // surface it as a backend error rather than unwinding through a panic.
        let guard = self
            .store
            .lock()
            .map_err(|_| CheckpointError::Backend("checkpoint mutex poisoned".to_string()))?;
        Ok(guard.get(task_id).cloned())
    }

    fn save(&self, task_id: &str, state: &SdkCheckpointState) -> Result<(), CheckpointError> {
        let mut guard = self
            .store
            .lock()
            .map_err(|_| CheckpointError::Backend("checkpoint mutex poisoned".to_string()))?;
        guard.insert(task_id.to_string(), state.clone());
        Ok(())
    }
}

/// ADR-037 决策三 — cooperative two-phase cancel signal raised from `commit`.
///
/// `commit` raises this **after** the business batch + checkpoint were committed
/// (i.e. at a clean batch boundary, never mid-batch), carrying the
/// already-persisted safe-point. A handler must propagate it up — **捕获并抑制它就停不
/// 下来** (swallowing it defeats cancellation). The run path / testkit map a
/// returned `SdkTaskStopped` to a **cancelled** terminal report, *not* a
/// failure: the data left behind is consistent at `break_position`.
#[derive(Debug, Clone, PartialEq)]
pub struct SdkTaskStopped {
    /// The committed safe-point at which the task stopped.
    pub break_position: BreakPosition,
}

impl SdkTaskStopped {
    /// Construct a stop signal at the given committed break position.
    pub fn at(break_position: BreakPosition) -> Self {
        Self { break_position }
    }
}

impl fmt::Display for SdkTaskStopped {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "task stopped (cancelled) at safe-point with {} break-position key(s)",
            self.break_position.len()
        )
    }
}

impl std::error::Error for SdkTaskStopped {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn in_memory_load_save_round_trip() {
        let cp = InMemoryCheckpoint::new();
        // First run: nothing persisted yet.
        assert_eq!(cp.load("t1").expect("load"), None);

        let mut bp = BreakPosition::new();
        bp.insert("id".to_string(), JsonValue::Int(42));
        let state = SdkCheckpointState {
            break_position: bp.clone(),
            succeed_count: 10,
            fail_count: 1,
            completed: false,
        };
        cp.save("t1", &state).expect("save");

        let loaded = cp.load("t1").expect("load").expect("present");
        assert_eq!(loaded, state);
        assert_eq!(loaded.break_position.get("id").and_then(JsonValue::as_i64), Some(42));
        // Distinct task id still empty.
        assert_eq!(cp.load("other").expect("load"), None);
    }

    #[test]
    fn save_overwrites_previous_state() {
        let cp = InMemoryCheckpoint::new();
        cp.save("t1", &SdkCheckpointState { succeed_count: 1, ..Default::default() })
            .expect("save");
        cp.save("t1", &SdkCheckpointState { succeed_count: 5, completed: true, ..Default::default() })
            .expect("save");
        let loaded = cp.load("t1").expect("load").expect("present");
        assert_eq!(loaded.succeed_count, 5);
        assert!(loaded.completed);
    }

    #[test]
    fn json_value_from_conversions() {
        assert_eq!(JsonValue::from("k"), JsonValue::Str("k".to_string()));
        assert_eq!(JsonValue::from(7i64), JsonValue::Int(7));
        assert_eq!(JsonValue::from(true), JsonValue::Bool(true));
        assert_eq!(JsonValue::Num(3.0).as_i64(), Some(3));
    }

    #[cfg(feature = "serde")]
    #[test]
    fn state_serializes_as_plain_json() {
        // `JsonValue` is `untagged`, so it round-trips as ordinary JSON and the
        // state uses the camelCase wire field names (Java contract parity).
        let state = SdkCheckpointState {
            break_position: {
                let mut m = BreakPosition::new();
                m.insert("id".to_string(), JsonValue::Int(42));
                m
            },
            succeed_count: 10,
            fail_count: 1,
            completed: false,
        };
        let json = serde_json::to_string(&state).expect("ser");
        assert!(json.contains("\"breakPosition\""));
        assert!(json.contains("\"succeedCount\":10"));
        assert!(json.contains("\"id\":42"));
        let back: SdkCheckpointState = serde_json::from_str(&json).expect("de");
        assert_eq!(back, state);
    }

    #[test]
    fn stopped_carries_break_position() {
        let mut bp = BreakPosition::new();
        bp.insert("row".to_string(), JsonValue::Int(99));
        let stopped = SdkTaskStopped::at(bp.clone());
        assert_eq!(stopped.break_position, bp);
        assert!(stopped.to_string().contains("cancelled"));
    }
}
