//! Phase 2 — the runtime **engine** for the BYO worker SDK.
//!
//! Built on top of the phase-1 decision core (`crate::decide`) and wire types
//! (`crate::protocol`): every runtime decision (HTTP classification, heartbeat
//! directive, lease renew, backpressure, stop plan, register) routes through the
//! pure phase-1 functions; this layer adds the IO **seams** (traits), the
//! synchronous **engine** (schedulers + lifecycle FSM + consumer pipeline), and
//! std-only **fakes** for testing.
//!
//! **Zero external dependencies** — std only. Real HTTP/Kafka clients are future
//! adapters behind [`transport::Transport`] and [`consumer::Consumer`]; here
//! they are documented stubs ([`transport::HttpTransport`]) plus working fakes.
//! Asynchrony in the TS/Go reference is rendered synchronously with
//! `Arc<AtomicBool>` cancel flags + per-cycle `tick()` methods, which keeps the
//! engine deterministic and unit-testable without threads or sockets.
//!
//! Module map:
//! * [`transport`] — `Transport` trait, status→action via `classify_http`,
//!   `HttpTransport` stub, `FakeTransport`.
//! * [`scheduler`] — `HeartbeatScheduler` (§1.3, dynamic interval) +
//!   `LeaseRenewalScheduler` (§1.4).
//! * [`lifecycle`] — `Worker` 4-state FSM (§1.5) + `start`/`stop` (§1.6).
//! * [`consumer`] — `Consumer` trait + `on_message` pipeline (§1.2/§1.9).
//! * [`sensitive`] — `SensitiveValidator` (§1.8).
//! * [`handler`] — handler SPI: `TaskContext`, `CancellationSignal`,
//!   `ProgressReporter`, `TaskResult`, `TaskHandler`.
//! * [`testkit`] — `FakePlatform` end-to-end rig.

pub mod consumer;
pub mod handler;
pub mod lifecycle;
pub mod scheduler;
pub mod sensitive;
pub mod testkit;
pub mod transport;

/// Real blocking control-plane transport (reqwest), behind the optional `http`
/// feature. The default build excludes this module entirely, keeping the core
/// crate std-only — mirroring how the `kafka` feature gates the Kafka adapter.
#[cfg(feature = "http")]
pub mod reqwest_transport;

// Ergonomic re-exports mirroring the TS/Go phase-2 surface.
pub use consumer::{Consumer, FakeConsumer, MessageOutcome, TaskRecord};
pub use handler::{
    CancellationSignal, NoopProgressReporter, ProgressReporter, TaskContext, TaskHandler,
    TaskResult,
};
pub use lifecycle::{StopReport, StopStep, Worker, WorkerState};
pub use scheduler::{
    HeartbeatScheduler, HeartbeatTick, LeaseRenewalScheduler, RenewTick,
    DEFAULT_HEARTBEAT_INTERVAL_MS, DEFAULT_LEASE_RENEW_INTERVAL_MS,
};
pub use sensitive::{SensitiveValidator, Validation};
pub use testkit::FakePlatform;
#[allow(deprecated)] // HttpTransport is a deprecated stub re-exported to pin the trait surface
pub use transport::{
    classify_response, outcome_from_decision, FakeTransport, HttpResponse, HttpTransport,
    Transport, TransportOutcome,
};
#[cfg(feature = "http")]
pub use reqwest_transport::{ReqwestConfig, ReqwestTransport, TransportBuildError};
