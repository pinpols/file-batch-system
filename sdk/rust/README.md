# batch-worker-sdk (Rust)

Rust BYO worker SDK decision core + runtime engine + conformance runner for the
file-batch-system control plane. It mirrors the Go (`../go`) and TypeScript
(`../typescript`) reference implementations 1:1 in behavior: pure, IO-free decision
functions (`src/decide.rs`) that map a protocol input (HTTP status, heartbeat
directive, lease renew, capacity / stop signal) to a `Decision` whose fields are the
closed `then.expect` vocabulary of the contract fixtures, plus the cross-language
shared constants kept honest against `docs/api/sdk-shared-constants.yaml`.

The default build keeps the runtime core dependency-light: protocol decisions and
the conformance runner are std-only, with a tiny `getrandom` dependency used only
for CSPRNG-backed idempotency keys and signing nonces. The runner drives all
`docs/api/sdk-contract-fixtures` by routing off each fixture's `when` shape
(never `then.expect`).

**最低环境要求**:默认 feature 的轻量核心为 **Rust 1.75+**(edition 2021，
`Cargo.toml` 的 `rust-version` 为核心 MSRV 声明)。启用生产 `http` 或 `kafka`
适配器时要求 **Rust 1.88+**，该下限来自当前锁定的可选依赖树；适配器 CI 使用 stable。

## Layout

```
src/
  decide.rs / protocol.rs / constants.rs   decision core + wire types + shared constants
  client/                                  runtime engine (transport / scheduler / lifecycle
                                           / consumer / sensitive / handler / resilience / testkit)
  client/reqwest_transport.rs              real blocking control-plane client — feature-gated `http`
  kafka.rs                                 real Kafka consumer adapter — feature-gated `kafka`
tests/
  contract.rs                              drives the contract fixtures' then.expect
  constants_parity.rs                      asserts constants == sdk-shared-constants.yaml
  runtime.rs                               runtime engine integration tests
```

## Test

```sh
cargo test                     # decision core + runtime + conformance + parity (zero-dep)
cargo test  --features http    # + ReqwestTransport tests (hand-rolled mock HTTP server)
cargo check --features kafka   # compile the rdkafka adapter (needs cmake + libssl)
```

The `kafka` feature pulls `rdkafka` (real SASL/SCRAM consumer); it is optional so the
core stays dependency-free. The end-to-end Kafka integration test is env-gated
(`BATCH_SDK_KAFKA_BOOTSTRAP`) and runs only against a live broker / in CI.

When wiring heartbeat control into a custom poll loop, use
`KafkaTaskConsumer::run_controlled(in_flight, platform_paused, keep_running)` so a
PAUSED worker stops claiming without leaving its consumer group; clearing the
pause gate resumes polling. `run(in_flight, keep_running)` remains available for
workers that do not need an external platform-pause gate.

## 实际执行入口确认

Rust 的 `MessageHandler` 由宿主自定义。启用 `http` 或 `kafka` 特性后，在 CLAIM 成功并将权威响应应用到 `TaskContext` 后，使用 `client::execute_after_start_confirmation` 包裹实际业务调用；不能在消费入队或等待名额时提前确认。

```rust,no_run
use batch_worker_sdk::client::{
    execute_after_start_confirmation, ExecutionStartError, ExecutionStartOutcome,
    TaskContext, TaskHandler, TaskResult, Transport,
};

fn execute_claimed<T: Transport, H: TaskHandler>(
    transport: &T, handler: &H, context: &TaskContext, worker_code: &str,
) -> Result<ExecutionStartOutcome<TaskResult>, ExecutionStartError> {
    execute_after_start_confirmation(transport, context, worker_code, || {
        handler.execute(context)
    })
}
```

该入口通过原续租端点发送当前租户、Worker、invocation 和 `details.executionStarted=true`，仅在确认成功后执行闭包；取消结果不调用闭包，租约拒绝、传输失败或无效响应返回 `ExecutionStartError`。宿主遇到该错误应保留原租约恢复流程，不发送伪造的业务失败回报。该计时信号不代替非幂等外部副作用开始标记，也不授权 Replay。

## P1 retry / idempotency

The runtime stays thin: retry and idempotency are explicit handler wrappers, not
business templates and not framework wiring.

```rust
use std::sync::Arc;
use std::time::Duration;
use batch_worker_sdk::client::{
    task_id_idempotency_key, with_idempotency, with_retry,
    InMemoryIdempotencyStore, RetryPolicy,
};

let handler = with_retry(
    with_idempotency(
        my_handler,
        Arc::new(InMemoryIdempotencyStore::new()),
        Duration::from_secs(3600),
        task_id_idempotency_key,
    ),
    RetryPolicy::default(),
);
```

Production idempotency stores implement `SdkIdempotencyStore`; the SDK ships only
`NoopIdempotencyStore` and `InMemoryIdempotencyStore`.

## Real HTTP transport (`http` feature)

The default build ships only the decision core + the `FakeTransport`; the
deprecated `HttpTransport` is a panicking stub. Enable the `http` feature to get
`ReqwestTransport`, a **real** blocking control-plane client (reqwest, rustls —
no OpenSSL) that talks to the platform's `/internal/*` endpoints. It performs one
round-trip per call and returns the raw `HttpResponse`; retry / backoff /
fail-fast stay in the engine (`classify_response` → `classify_http`). Headers
match the Java/Python/Go SDKs: `X-Batch-Tenant-Id`, `X-Batch-Api-Key`, and
`Idempotency-Key` on `claim`/`report`.

```rust
use batch_worker_sdk::client::{ReqwestConfig, ReqwestTransport, Transport};

let cfg = ReqwestConfig::new("https://orch.internal:8080", "tenant-42")
    .with_api_key(std::env::var("BATCH_API_KEY").unwrap_or_default())
    .with_max_concurrent_tasks(4)
    .with_timeouts(5_000, 10_000); // connect_ms, read_ms (keep read < heartbeat/3)
let transport = ReqwestTransport::new(cfg)?;        // returns Result, no unwrap on I/O
let resp = transport.register("worker-1", r#"{"workerCode":"worker-1","tenantId":"tenant-42"}"#);
// feed `resp` to the engine (lifecycle/scheduler) — it classifies status → action.
```

### 新 Orchestrator 的 CLAIM 参数

CLAIM 成功后，先按 HTTP 状态分类，再用 `decode_claim_response(&resp)` 解码成功回包；409 表示任务已被认领，不能继续执行。handler 上下文通过 `TaskContext::with_claim_response(&claim)` 接收 CLAIM 的 `payload`、`traceId` 和 `partitionInvocationId`。载荷解析要求 JSON 对象；格式无效时返回错误并应将任务报告为失败。嵌套的 `crossDay` 依赖快照会保留为 `JsonValue`，不会被压平。该解码 API 需要 `serde` feature，启用 `http` 或 `kafka` 时会自动带入。

```rust
use batch_worker_sdk::client::{decode_claim_response, TaskContext, TransportOutcome};

let claim_response = transport.claim("task-7", r#"{"tenantId":"tenant-42","workerId":"worker-1"}"#);
if matches!(batch_worker_sdk::client::classify_response(&claim_response, 0), TransportOutcome::Success) {
    let claim = decode_claim_response(&claim_response)?;
    let context = TaskContext::new("task-7", "tenant-42", "PROCESS")
        .with_claim_response(&claim)?;
    handler.execute(&context);
}
```

## Conformance contract

This SDK must satisfy `docs/sdk/byo-conformance-contract.md`: consume
`docs/api/sdk-shared-constants.yaml` (no re-authored literals), pass every fixture's
machine-assertable `then.expect`, and plug into `.github/workflows/sdk-contract-parity.yml`
(`rust-contract` job). Protocol authority: `docs/sdk/wire-protocol.md` §A/§B/§C.
