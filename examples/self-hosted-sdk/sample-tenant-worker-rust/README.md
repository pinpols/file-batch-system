# sample-tenant-worker-rust

A minimal, **illustrative** self-hosted tenant worker built on the Rust BYO SDK
(`batch-worker-sdk`, at `sdk/rust`). It mirrors the Go
(`examples/sample-tenant-worker-go`) and Python
(`examples/sample-tenant-worker-python`) samples.

## Connects for real

Both adapters are real and wired end-to-end (no stubs, nothing commented out):

- the **Kafka** consumer is real (rdkafka, behind the `kafka` feature) and runs
  a live poll loop;
- the **control-plane** transport is real (`ReqwestTransport`, behind the `http`
  feature): `register` → `claim` → `execute` → `report` → `deactivate` all hit
  the live orchestrator, and the **heartbeat** (§1.3) + **lease-renewal** (§1.4)
  schedulers run on their own threads.

This example is compiled by CI and runs against a live stack. `cmake` is required
to build rdkafka (see the SDK README).

> **Still a sample, not turnkey production.** The echo handler runs synchronously
> and never blocks, so the in-flight registry the lease-renewal loop iterates is
> usually empty between polls — the wiring is real, but a production worker with
> long-running/async tasks is what actually exercises lease renewal +
> cooperative cancel. The SIGTERM hook is also a documented no-op (see below): a
> production worker installs a real signal handler that flips the SDK stop flag.

## What it shows

1. **Config from env**, failing fast and listing *every* missing required var at
   once (not one per restart). Credentials come from env / secret, never from a
   message payload (§1.8).
2. Building the real Kafka consumer adapter: `KafkaConsumerConfig` +
   `KafkaTaskConsumer` — PLAINTEXT locally, SASL/SCRAM-SHA-512 when the SASL
   vars are set.
3. An echo-style **business handler** implementing the `TaskHandler` SPI, plus a
   `HandlerBridge` that adapts the Kafka adapter's per-message callback
   (`MessageHandler`) to it: it runs the **claim → execute → report** lifecycle,
   **skips execution on a 409/already-claimed** (no double side-effects), and
   **guards the handler with `catch_unwind`** so a panicking handler is reported
   as a fail (`EXECUTION_FAILED`) instead of killing the worker.
4. The **worker lifecycle** (`Worker` 4-state FSM, §1.5) with a `request_stop()`
   SIGTERM hook (`Worker::stop_flag()`) and the graceful `stop(30_000)` sequence
   (§1.6).
5. The **heartbeat** (`HeartbeatScheduler`) and **lease-renewal**
   (`LeaseRenewalScheduler`) loops on background threads: heartbeats keep the
   worker live and apply a DRAIN directive (flip the stop flag); lease renewal
   keeps in-flight leases alive and delivers `cancelRequested` to the running
   task's cancel signal.

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

cargo run
```

This crate's `Cargo.toml` already enables the SDK's `kafka` + `http` features
(`batch-worker-sdk = { ..., features = ["kafka", "http"] }`), so a bare
`cargo run` builds both the real Kafka consumer and the real control-plane
transport. Building rdkafka needs `cmake` on the `PATH`.

## Make it yours

Swap `EchoHandler` for your business logic (implement `TaskHandler::execute`).
For a production worker you should also:

- install a **real SIGTERM handler** (e.g. the `signal_hook` crate) that flips
  the SDK stop flag — the sample's hook is a documented no-op (see `main.rs`);
- run tasks **asynchronously** (a bounded executor) and increment/decrement the
  `in_flight` counter around execution so backpressure (§1.5) and lease renewal
  (§1.4) reflect real concurrency — the echo handler here is synchronous.
