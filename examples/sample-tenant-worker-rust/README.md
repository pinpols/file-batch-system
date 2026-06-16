# sample-tenant-worker-rust

A minimal, **illustrative** self-hosted tenant worker built on the Rust BYO SDK
(`batch-worker-sdk`, at `sdk/rust`). It mirrors the Go
(`examples/sample-tenant-worker-go`) and Python
(`examples/sample-tenant-worker-python`) samples.

## ⚠️ Illustrative — not yet runnable end-to-end

The SDK's control-plane HTTP transport (`HttpTransport`) is a **documented
stub**: the Rust std library ships no HTTP client and the SDK core is
zero-dependency, so the real `register` / `heartbeat` / `claim` / `report` /
`renew` calls land with a **future reqwest adapter**. Until then:

- the **Kafka** path is real (rdkafka, behind the `kafka` feature) and this
  binary is shaped to run a live poll loop;
- the **control-plane** calls (`worker.start()` / `worker.stop()`) are wired but
  **commented out**, because calling the stub panics by design. Each call site
  shows exactly where the real call goes once a real `Transport` is supplied.

This example is **compiled by CI** (and runs fully once the reqwest adapter
lands). It is intentionally not built in the dev sandbox here, where the Rust
CDN is unreachable so `cargo` cannot fetch crates.

## What it shows

1. **Config from env**, failing fast and listing *every* missing required var at
   once (not one per restart). Credentials come from env / secret, never from a
   message payload (§1.8).
2. Building the real Kafka consumer adapter: `KafkaConsumerConfig` +
   `KafkaTaskConsumer` — PLAINTEXT locally, SASL/SCRAM-SHA-512 when the SASL
   vars are set.
3. An echo-style **business handler** implementing the `TaskHandler` SPI, plus a
   `HandlerBridge` that adapts the Kafka adapter's per-message callback
   (`MessageHandler`) to it (builds a `TaskContext`, dispatches, maps the
   `TaskResult` back to an offset-commit / retry decision).
4. The **worker lifecycle** (`Worker` 4-state FSM, §1.5) with a `request_stop()`
   SIGTERM hook (`Worker::stop_flag()`), and the graceful `stop(30_000)`
   sequence (§1.6) — both shown at their real call sites.

## Environment variables

| Variable | Required | Purpose |
|---|---|---|
| `BATCH_BASE_URL` | yes | Control-plane base URL (e.g. `https://platform.example.com`). |
| `BATCH_API_KEY` | yes | API key for control-plane auth (used by the reqwest adapter). |
| `BATCH_TENANT_ID` | yes | The worker's own tenant id (drives the §1.9 self-check + topic subscription). |
| `BATCH_WORKER_CODE` | yes | This worker's code (drives the consumer group id). |
| `KAFKA_BOOTSTRAP` | yes | Kafka bootstrap servers, e.g. `broker-1:9092,broker-2:9092`. |
| `KAFKA_SASL_SECURITY_PROTOCOL` | no | e.g. `SASL_SSL`. Unset → PLAINTEXT (local dev). |
| `KAFKA_SASL_MECHANISM` | no | Defaults to `SCRAM-SHA-512` when a username is set. |
| `KAFKA_SASL_USERNAME` | no | SASL username; presence switches on the SASL block. |
| `KAFKA_SASL_PASSWORD` | no | SASL password. |

## Run

```sh
export BATCH_BASE_URL=https://platform.example.com
export BATCH_API_KEY=...                # never echoed; read from env/secret
export BATCH_TENANT_ID=tenant-a
export BATCH_WORKER_CODE=worker-1
export KAFKA_BOOTSTRAP=localhost:9092
# optional SASL/SSL:
# export KAFKA_SASL_SECURITY_PROTOCOL=SASL_SSL
# export KAFKA_SASL_USERNAME=svc-tenant-a
# export KAFKA_SASL_PASSWORD=...

cargo run --features kafka
```

The `kafka` feature is forwarded to the SDK by this crate's `Cargo.toml`
(`features = ["kafka"]`), so `cargo run` alone also enables it; the explicit
flag above matches the Go/Python sample docs and is harmless.

## Make it yours

Swap `EchoHandler` for your business logic (implement `TaskHandler::execute`),
and — once the reqwest adapter lands — uncomment the `worker.start()` /
`worker.stop()` call sites and pass a real `Transport` in place of
`HttpTransport`.
