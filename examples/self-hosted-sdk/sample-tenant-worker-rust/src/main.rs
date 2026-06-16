//! `sample-tenant-worker-rust` — a minimal, *illustrative* self-hosted worker
//! built on the Rust BYO SDK (`batch_worker_sdk`).
//!
//! It mirrors the Go (`examples/sample-tenant-worker-go`) and Python
//! (`examples/sample-tenant-worker-python`) samples and demonstrates the
//! smallest end-to-end wiring a tenant needs:
//!
//!   1. read config + credentials from the environment (never from payloads),
//!   2. build the real Kafka consumer adapter (`KafkaConsumerConfig` +
//!      `KafkaTaskConsumer`) — PLAINTEXT locally, SASL/SCRAM-SHA-512 when set,
//!   3. implement an echo-style business handler (`TaskHandler`) and bridge it
//!      to the adapter's per-message callback (`MessageHandler`),
//!   4. drive the worker lifecycle (`Worker` FSM start → run → graceful stop)
//!      with a `request_stop()` SIGTERM hook.
//!
//! ## ⚠️ Illustrative — does not connect yet
//!
//! The SDK's control-plane HTTP transport ([`HttpTransport`]) is a **documented
//! stub**: std ships no HTTP client and the SDK is zero-dependency, so the real
//! register/heartbeat/claim/report/renew calls land with the future **reqwest**
//! adapter. Every call site that depends on that path is commented inline. The
//! Kafka adapter *is* real (rdkafka behind the `kafka` feature); this binary is
//! shaped to run, and is compiled by CI / once the reqwest adapter lands. See
//! `README.md`.

use std::collections::BTreeMap;
use std::env;
use std::process;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use batch_worker_sdk::client::handler::{
    NoopProgressReporter, TaskContext, TaskHandler, TaskResult,
};
use batch_worker_sdk::client::lifecycle::Worker;
use batch_worker_sdk::client::sensitive::SensitiveValidator;
use batch_worker_sdk::client::transport::HttpTransport;
use batch_worker_sdk::kafka::{
    DispatchMessage, KafkaConsumerConfig, KafkaTaskConsumer, MessageHandler,
};

fn main() {
    // ── (1) Config — fail fast listing every missing required var at once. ──
    let cfg = match Config::from_env() {
        Ok(c) => c,
        Err(missing) => {
            eprintln!(
                "[sample-worker] FATAL config error: missing required env vars: {}",
                missing.join(", ")
            );
            process::exit(1);
        }
    };

    log(&format!(
        "starting worker code={} tenant={} base={} brokers={}",
        cfg.worker_code, cfg.tenant_id, cfg.base_url, cfg.kafka_bootstrap
    ));

    // ── (1b) Register-time credential-leak guard (§1.8). ───────────────────
    // Credentials must travel via env / secret, never in the register body.
    // The SDK validator scans the *keys* of any payload; here we attest that
    // the static register attributes carry no sensitive values.
    let validator = SensitiveValidator::new();
    let register_attrs: [(&str, &str); 2] =
        [("buildId", "sample-tenant-worker-rust@dev"), ("sdkVersion", "rust-byo-sdk")];
    if validator.validate(register_attrs).is_rejected() {
        eprintln!("[sample-worker] FATAL register attributes carry a sensitive value");
        process::exit(1);
    }

    // ── (2) Real Kafka consumer adapter (rdkafka, `kafka` feature). ────────
    // PLAINTEXT locally; when SASL vars are set it negotiates SASL/SCRAM-SHA-512.
    let kafka_cfg = KafkaConsumerConfig {
        bootstrap_servers: cfg.kafka_bootstrap.clone(),
        tenant_id: cfg.tenant_id.clone(),
        worker_code: cfg.worker_code.clone(),
        max_concurrent: 4,
        poll_interval: Duration::from_millis(500),
        security_protocol: cfg.sasl_security_protocol.clone(),
        sasl_mechanism: cfg.sasl_mechanism.clone(),
        sasl_username: cfg.sasl_username.clone(),
        sasl_password: cfg.sasl_password.clone(),
    };

    // ── (3) Business handler (the SPI a tenant implements) + the bridge that
    // turns each accepted dispatch message into a `TaskContext` and invokes it. ─
    let bridge = HandlerBridge {
        handler: EchoHandler,
        tenant_id: cfg.tenant_id.clone(),
    };

    let mut consumer = match KafkaTaskConsumer::new(kafka_cfg, bridge) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("[sample-worker] FATAL kafka consumer: {e}");
            process::exit(1);
        }
    };

    // ── (4) Worker lifecycle FSM (§1.5/§1.6). ──────────────────────────────
    // NOTE: `HttpTransport` is the documented stub — its register/heartbeat/…
    // methods are `unimplemented!()` until the reqwest adapter lands. So in this
    // illustrative build we DO NOT call `worker.start()` (it would panic in the
    // stub's `register`). The wiring below shows exactly where the real call
    // goes; uncomment once a real `Transport` (reqwest) is supplied here.
    let transport = HttpTransport::new(&cfg.base_url);
    let mut worker = Worker::new(&cfg.worker_code, transport);

    // (4a) SIGTERM hook. std has no portable async-signal-safe handler, so the
    // documented integration point is `Worker::request_stop()` / `stop_flag()`:
    // a tenant signal handler (e.g. the `signal_hook` crate, or raw
    // `libc::sigaction`) flips the shared flag, and the run loop below observes
    // it and runs `stop(30_000)` within K8s' 30s grace window. We install a
    // best-effort handler when `signal_hook` is available; absent that, the
    // `keep_running` closure still honours the same flag.
    let stop_flag: Arc<AtomicBool> = worker.stop_flag();
    install_sigterm_hook(Arc::clone(&stop_flag));

    // (4b) Register with the control plane. ILLUSTRATIVE: guarded out because
    // the stub panics. With a real transport this is the FSM's NORMAL entry.
    //
    //     match worker.start("{}", /* idempotent = */ false) {
    //         Ok(decision) => log(&format!("registered: {decision:?}")),
    //         Err(outcome) => {
    //             eprintln!("[sample-worker] FATAL register failed: {outcome:?}");
    //             process::exit(1);
    //         }
    //     }
    let _ = &mut worker; // silence "unused mut" until start()/stop() are live.
    log("worker FSM constructed (register deferred to the reqwest adapter)");

    // ── (5) Run loop: poll Kafka until the stop flag flips. ────────────────
    // `in_flight` would be incremented/decremented around real task execution;
    // here it stays 0 (the echo handler returns synchronously) but is plumbed
    // so backpressure (§1.5) reflects real concurrency once execution is async.
    let in_flight = Arc::new(AtomicI64::new(0));
    let if_read = {
        let in_flight = Arc::clone(&in_flight);
        move || in_flight.load(Ordering::SeqCst)
    };
    let keep_running = {
        let stop_flag = Arc::clone(&stop_flag);
        move || !stop_flag.load(Ordering::SeqCst)
    };

    log("entering Kafka poll loop (Ctrl-C / SIGTERM to drain)");
    if let Err(e) = consumer.run(if_read, keep_running) {
        eprintln!("[sample-worker] FATAL kafka run loop: {e}");
        process::exit(1);
    }

    // ── (6) Graceful stop (§1.6: drain → shut executor → deactivate). ──────
    // ILLUSTRATIVE: `stop()` calls the stub's `deactivate` (panics) until the
    // reqwest adapter lands. With a real transport:
    //
    //     let report = worker.stop(30_000);
    //     log(&format!("stopped cleanly: {:?}", report.steps));
    log("poll loop exited; graceful stop deferred to the reqwest adapter");
}

// ───────────────────────────────────────────────────────────────────────────
// Business handler — the SPI a tenant implements.
// ───────────────────────────────────────────────────────────────────────────

/// The example business SPI: logs the task and echoes the effective parameters
/// back as outputs, mirroring the Java/Go/Python `echo` samples.
struct EchoHandler;

impl TaskHandler for EchoHandler {
    fn task_type(&self) -> &str {
        "sample-echo"
    }

    fn execute(&self, ctx: &TaskContext) -> TaskResult {
        log(&format!(
            "echo handler taskId={} traceId={} params={}",
            ctx.task_id,
            ctx.trace_id,
            ctx.parameters.len()
        ));

        // Cooperative cancellation: bail early if the lease was cancelled.
        if ctx.is_cancelled() {
            return TaskResult::cancelled("task cancelled before echo");
        }

        // Echo each effective parameter back as an output artifact.
        let mut result = TaskResult::success(&format!("echoed {} param(s)", ctx.parameters.len()))
            .with_output("handledBy", "sample-tenant-worker-rust");
        for (k, v) in &ctx.parameters {
            result = result.with_output(k, v);
        }
        result
    }
}

// ───────────────────────────────────────────────────────────────────────────
// Adapter bridge — `MessageHandler` (Kafka-facing) → `TaskHandler` (business).
// ───────────────────────────────────────────────────────────────────────────

/// Adapts the Kafka adapter's per-message callback ([`MessageHandler`]) to the
/// engine's business SPI ([`TaskHandler`]). The adapter has already run the
/// schema-reject / tenant self-check / backpressure pipeline (§A/§1.9/§1.5)
/// before calling [`MessageHandler::on_accepted`], so here we only build a
/// [`TaskContext`] from the decoded dispatch and dispatch to the handler.
///
/// In a complete worker this is also where the claim → execute → report
/// lifecycle lives; returning `Err` withholds the Kafka offset commit so the
/// record is re-read (the Java `RETRY_LATER` path).
struct HandlerBridge<H: TaskHandler> {
    handler: H,
    tenant_id: String,
}

impl<H: TaskHandler> MessageHandler for HandlerBridge<H> {
    fn on_accepted(&mut self, msg: &DispatchMessage) -> Result<(), String> {
        // Build the per-task context from the decoded dispatch payload. The
        // `parameters` map would, in the real worker, come from the claim
        // response snapshot; here we surface the raw `extra` JSON keys so the
        // echo handler has something to echo.
        let ctx = TaskContext {
            task_id: msg.task_id.clone(),
            tenant_id: if msg.tenant_id.is_empty() {
                self.tenant_id.clone()
            } else {
                msg.tenant_id.clone()
            },
            task_type: msg.task_type.clone(),
            trace_id: String::new(),
            parameters: extract_params(msg),
            cancellation: Default::default(),
            progress: Box::new(NoopProgressReporter),
        };

        let result = self.handler.execute(&ctx);
        log(&format!(
            "task {} -> errorCode={} summary={:?}",
            ctx.task_id, result.error_code, result.result_summary
        ));

        // The real worker reports `result` to the control plane here (POST
        // /internal/tasks/{id}/report) before returning Ok to commit the offset.
        // That call rides the reqwest adapter; for now a successful handler run
        // commits the offset, a non-success result withholds it for re-read.
        if result.is_success() {
            Ok(())
        } else {
            Err(result.error_code)
        }
    }
}

/// Flatten the dispatch's raw `extra` JSON into the string→string parameter map
/// the [`TaskContext`] carries. Non-string scalars are stringified; nested
/// objects/arrays are rendered as compact JSON so nothing is silently dropped.
fn extract_params(msg: &DispatchMessage) -> BTreeMap<String, String> {
    let mut out = BTreeMap::new();
    for (k, v) in &msg.extra {
        let value = match v.as_str() {
            Some(s) => s.to_string(),
            None => v.to_string(),
        };
        out.insert(k.clone(), value);
    }
    out
}

// ───────────────────────────────────────────────────────────────────────────
// Config
// ───────────────────────────────────────────────────────────────────────────

/// Worker configuration, read entirely from the environment. Env var names
/// mirror the Java/Go samples (BATCH_* family) plus KAFKA_* for the broker.
struct Config {
    base_url: String,
    #[allow(dead_code)] // wired into the reqwest auth header once that lands.
    api_key: String,
    tenant_id: String,
    worker_code: String,
    kafka_bootstrap: String,
    // Optional SASL/SCRAM-SHA-512; PLAINTEXT when unset.
    sasl_security_protocol: Option<String>,
    sasl_mechanism: Option<String>,
    sasl_username: Option<String>,
    sasl_password: Option<String>,
}

impl Config {
    /// Read all required settings, returning the list of every missing required
    /// var (so the operator sees them all at once, not one per restart).
    fn from_env() -> Result<Config, Vec<String>> {
        let required = [
            "BATCH_BASE_URL",
            "BATCH_API_KEY",
            "BATCH_TENANT_ID",
            "BATCH_WORKER_CODE",
            "KAFKA_BOOTSTRAP",
        ];
        let mut missing = Vec::new();
        let mut values: BTreeMap<&str, String> = BTreeMap::new();
        for name in required {
            match env::var(name) {
                Ok(v) if !v.trim().is_empty() => {
                    values.insert(name, v.trim().to_string());
                }
                _ => missing.push(name.to_string()),
            }
        }
        if !missing.is_empty() {
            return Err(missing);
        }

        Ok(Config {
            base_url: values["BATCH_BASE_URL"].clone(),
            api_key: values["BATCH_API_KEY"].clone(),
            tenant_id: values["BATCH_TENANT_ID"].clone(),
            worker_code: values["BATCH_WORKER_CODE"].clone(),
            kafka_bootstrap: values["KAFKA_BOOTSTRAP"].clone(),
            // Optional; any set → the whole SASL block is forwarded by the SDK.
            sasl_security_protocol: opt_env("KAFKA_SASL_SECURITY_PROTOCOL"),
            sasl_mechanism: opt_env("KAFKA_SASL_MECHANISM"),
            sasl_username: opt_env("KAFKA_SASL_USERNAME"),
            sasl_password: opt_env("KAFKA_SASL_PASSWORD"),
        })
    }
}

/// `Some(trimmed)` when the env var is set and non-blank, else `None`.
fn opt_env(name: &str) -> Option<String> {
    env::var(name)
        .ok()
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
}

// ───────────────────────────────────────────────────────────────────────────
// SIGTERM hook (documented limitation)
// ───────────────────────────────────────────────────────────────────────────

/// Best-effort SIGTERM/SIGINT hook. std has no portable async-signal-safe
/// signal API, so a production worker wires a real handler (e.g. the
/// `signal_hook` crate, or a raw `libc::sigaction`) that flips the SDK's shared
/// stop flag — exactly the `stop_flag` we pass here. This sample keeps its
/// dependency surface to just the SDK, so the hook is a documented no-op:
/// the worker still drains when the flag flips by any means (a test, a future
/// signal-hook adapter, or a manual `worker.request_stop()`).
fn install_sigterm_hook(_stop_flag: Arc<AtomicBool>) {
    // Wiring with the `signal_hook` crate would be:
    //
    //     use signal_hook::consts::{SIGINT, SIGTERM};
    //     use signal_hook::flag;
    //     flag::register(SIGTERM, Arc::clone(&_stop_flag)).expect("register SIGTERM");
    //     flag::register(SIGINT, Arc::clone(&_stop_flag)).expect("register SIGINT");
    //
    // Left out so this example depends only on the SDK (no extra crates).
    log("SIGTERM hook is a documented no-op (add `signal_hook` to enable; see source)");
}

// ───────────────────────────────────────────────────────────────────────────
// Logging — minimal stdout logger (the samples avoid pulling a log crate).
// ───────────────────────────────────────────────────────────────────────────

fn log(msg: &str) {
    println!("[sample-worker] INFO {msg}");
}
