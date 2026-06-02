# batch-worker-sdk (Python)

Python SDK for the **file-batch-system** worker protocol — the async-only
counterpart to the Java [`batch-worker-sdk/`](../batch-worker-sdk/).

> **Phase 0 — scaffolding only.** This package currently exposes nothing
> but a version string. No HTTP client, no Kafka consumer, no handler
> runtime. **Do not use in production.** See the Roadmap below for the
> per-phase delivery plan.

## Status

| Aspect | State |
| --- | --- |
| Package layout | Done (Phase 0) |
| Tooling (ruff, mypy, pytest) | Done (Phase 0) |
| CI (`.github/workflows/sdk-python.yml`) | Done (Phase 0) |
| Contract-fixture runner | Stub (all xfail) — Phase 0 |
| Real `WorkerClient` / `HandlerContext` | Pending — Phase 1 |
| PyPI release | Pending — Phase 5 |

## Design decisions

- **Python 3.12+** — modern typing (`type` statement, PEP 695, generic
  `TypeVar` syntax) is non-negotiable.
- **Async-only** — handler signature is `async def handle(ctx) -> None`.
  No sync wrappers. If you need sync, run inside `asyncio.run`.
- **PyPI name `batch-worker-sdk`, import name `batch_worker_sdk`** —
  matches the Java module name, swapping `-` for `_` per PEP 8.
- **Version `0.0.1a0`** — PEP 440 normalized form of "0.0.1-alpha".
  hatchling rejects the dashed form.
- **Same monorepo** — lives under `sdk-python/` alongside the Java SDK.
  Independent toolchain; **NOT** part of the Maven reactor.

## Install (preview)

```bash
# Once Phase 5 ships and we publish to PyPI:
pip install batch-worker-sdk

# Until then, install from source:
git clone https://github.com/pinpols/file-batch-system.git
cd file-batch-system/sdk-python
pip install -e .[dev]
```

## Roadmap

| Phase | Scope | Est. effort |
| --- | --- | --- |
| **P0** ✅ | Scaffolding: pyproject, ruff, mypy, pytest, CI, contract stub | done |
| **P0.5** ✅ | Public API surface stubs (handler / context / result / state / progress / cancellation / descriptor) mirroring Java SDK | done (Lane R) |
| **P1** ✅ | `PlatformHttpClient` (httpx async) + retry/backoff + `BatchPlatformClientConfig` | done (Lane Q) |
| **P2** ✅ | Kafka consumer (aiokafka), `TaskDispatcher` (CLAIM/EXECUTE/REPORT), capacity-aware pause, tenant self-check, provisional `run_worker` entrypoint | done (Lane S) |
| **P3** ✅ | `BatchPlatformClient` + `HeartbeatScheduler` + `LeaseRenewalScheduler` + heartbeat-directive parsing | done (Lane T) |
| **P4** ✅ | Lifecycle: budgeted `stop(timeout)`, cancellation signal wiring, progress reporter, deactivate | done (Lane U) |
| **P5** ✅ | Testkit (`FakeBatchPlatform`) + `@batch_task` decorator + `examples/sample-tenant-worker-python/` | done (Lane V) |

Contract fixtures from Lane N drive the green-bar for P1–P3. Every
fixture that flips from `xfail` to `pass` is forward progress.

## Usage (P3)

```python
import asyncio
from datetime import timedelta

from batch_worker_sdk import BatchPlatformClient, BatchPlatformClientConfig

class MyImportHandler:
    def task_type(self) -> str:
        return "tenant_xyz_import"

    async def execute(self, ctx):  # SdkTaskContext
        return ...  # SdkTaskResult

async def main() -> None:
    config = BatchPlatformClientConfig(
        base_url="https://batch.example.com",
        tenant_id="tenant-xyz",
        worker_code="xyz-import-worker-1",
        heartbeat_interval=timedelta(seconds=30),
        lease_renew_interval=timedelta(seconds=60),
    )
    client = BatchPlatformClient(config)
    client.register_handler(MyImportHandler())
    await client.start()  # register → heartbeat + lease schedulers → kafka
    try:
        await asyncio.Event().wait()  # block until SIGTERM
    finally:
        await client.stop(timeout=30)

asyncio.run(main())
```

## Equivalence with the Java SDK

The Python SDK is a **drop-in equivalent** of the Java one at the
**wire-protocol level** — same HTTP endpoints, same Kafka topics, same
payload shapes. APIs are idiomatic to each language:

| Concern | Java | Python (target) |
| --- | --- | --- |
| Module | [`batch-worker-sdk/`](../batch-worker-sdk/) | `sdk-python/` |
| Entry class | `WorkerClient` | `WorkerClient` |
| Handler signature | `void handle(HandlerContext ctx)` | `async def handle(ctx: HandlerContext) -> None` |
| Spring Boot integration | [`batch-worker-sdk-spring-boot-starter`](../batch-worker-sdk-spring-boot-starter/) | (none; use FastAPI / vanilla async) |
| Testkit | [`batch-worker-sdk-testkit`](../batch-worker-sdk-testkit/) | TBD (Phase 4) |

### Public API surface ↔ Java SDK (P0.5)

The 7 types below are 1:1 with their Java counterparts. Names follow
PEP 8 (snake_case methods, snake_case fields); semantics mirror the
Java side. P0.5 ships stubs only — implementation lands in P1-P5.

| Java | Python | Java path |
| --- | --- | --- |
| `SdkTaskHandler` (interface) | `SdkTaskHandler` (Protocol) | `batch-worker-sdk/.../task/SdkTaskHandler.java` |
| `SdkTaskContext` (record) | `SdkTaskContext` (pydantic BaseModel) | `batch-worker-sdk/.../task/SdkTaskContext.java` |
| `SdkTaskResult` (record) | `SdkTaskResult` (pydantic BaseModel) | `batch-worker-sdk/.../task/SdkTaskResult.java` |
| `WorkerRuntimeState` (enum) | `WorkerRuntimeState` (str Enum) | `batch-worker-sdk/.../dispatcher/WorkerRuntimeState.java` |
| `ProgressReporter` (class) | `ProgressReporter` (class) | `batch-worker-sdk/.../task/ProgressReporter.java` |
| `CancellationSignal` (class) | `CancellationSignal` (class) | `batch-worker-sdk/.../task/CancellationSignal.java` |
| `SdkTaskTypeDescriptor` (record) | `SdkTaskTypeDescriptor` (pydantic BaseModel) | `batch-worker-sdk/.../task/SdkTaskTypeDescriptor.java` |

### 与 Java SDK 行为对照 (P2)

Lane S (P2) 把 dispatcher + Kafka consumer 接上,对齐 Java SDK 下列 5 个语义点(行为照搬,代码风格 asyncio-native):

| # | 行为 | Java 路径 | Python 路径 |
| --- | --- | --- | --- |
| 1 | **租户自检 drop**:Kafka 消息 `tenantId != config.tenant_id` → ERROR log + drop,不 ack,不抛 | `TaskDispatcher.onMessage` line 197 (Lane J) | `dispatcher.TaskDispatcher.on_message` |
| 2 | **capacity-aware partition pause**:`inFlight >= maxConcurrentTasks` 或平台 PAUSED/DRAINING → `consumer.pause(*assignment)`;掉下来 `resume`。`_paused` 缓存避免重复 RPC | `KafkaTaskConsumer.applyBackpressure` | `_kafka.KafkaTaskConsumer.apply_backpressure` |
| 3 | **schemaVersion 守护**:未知 major (e.g. `v3`) → WARN + drop,避免老 SDK 误解未来消息 | `TaskDispatchMessage.isSchemaSupported` | `dispatcher._SUPPORTED_SCHEMA_PREFIXES` 列表 |
| 4 | **fatal poisoning**:CLAIM 401/403 → `_fatal=True`,后续 `on_message` 全部静默 drop,等 K8s liveness probe 回收 | `TaskDispatcher.fatal` AtomicBoolean | `TaskDispatcher._fatal` + `is_fatal` property |
| 5 | **4-state directive 应用**:heartbeat 返回 `runtimeState` ∈ `{NORMAL, DEGRADED, PAUSED, DRAINING}` → `apply_platform_directive()` 更新 `_runtime_state`;`accepts_new_tasks()` 决定是否拒新 | `TaskDispatcher.platformState` + `WorkerRuntimeState.acceptsNewTasks` | `TaskDispatcher.apply_platform_directive` + `WorkerRuntimeState.accepts_new_tasks` |

**Provisional API** (P3 替换):`runner.run_worker(config, handlers)` 临时把 HTTP / Dispatcher / Kafka 三件套串起来跑,等 P3 `BatchPlatformClient` 落地后改成 thin shim。源文件顶部用 `# Provisional API — Will be superseded by BatchPlatformClient in P3` 标注。

**P2 简化 vs Java**(明列出来,P3 接续清单):

- 没有 CLAIM 5xx 重试 loop —— Python `_http.PlatformHttpClient` 在 wire-protocol §C 层做过 retry,我们这里只 surface `TransientError` 让 Kafka lease timeout 重投。
- 没有 `ThrottledLogger`(Java Lane J #2)—— PAUSED-state drop 日志降到 DEBUG 避免刷屏,不引外部依赖。
- 没有 MDC —— 结构化字段以 `logging.LogRecord.extra` 形式传给用户的 formatter。

Source-of-truth docs (both SDKs read from these):

- [`docs/sdk/wire-protocol.md`](../docs/sdk/wire-protocol.md) — HTTP +
  Kafka protocol, failure-mode matrix.
- [`docs/sdk/byo-sdk-guide.md`](../docs/sdk/byo-sdk-guide.md) —
  Bring-Your-Own-SDK guide.
- [`docs/api/sdk-contract-fixtures/`](../docs/api/sdk-contract-fixtures/) —
  Lane N's JSON fixtures, the canonical conformance suite for **every**
  SDK implementation.

## Development

```bash
cd sdk-python
pip install -e .[dev]

# Lint + types
ruff check src tests
ruff format --check src tests
mypy src

# Tests
pytest -v

# Contract runner (Phase 0: lists fixtures, xfails them all)
bash scripts/run-contract.sh
```

## `@batch_task` decorator (P5)

Declarative handler registration in the spirit of FastAPI / Spring
starter. One `async def` + one decorator + one call to
`collect_registered_handlers()` at startup:

```python
from batch_worker_sdk import batch_task, SdkTaskContext, SdkTaskResult, collect_registered_handlers

@batch_task("my-job")
async def my_handler(ctx: SdkTaskContext) -> SdkTaskResult:
    return SdkTaskResult.success_with({"hello": "world"})

# At startup:
for handler in collect_registered_handlers():
    client.register_handler(handler)  # Lane T (P3)
```

Sync functions and empty `task_type` are rejected at decorator time
(fail-fast). Optional `descriptor=` lets you declare a
`SdkTaskTypeDescriptor` for the console form renderer; the decorator
verifies it matches `task_type` so the wire-protocol can never desync.

## testkit (P5)

`batch_worker_sdk.testkit` ships an in-process platform fake so
tenant tests don't need Kafka/orchestrator:

```python
from batch_worker_sdk.testkit import FakeBatchPlatform, make_test_config

async with FakeBatchPlatform() as fp:
    cfg = make_test_config(base_url=fp.base_url)
    # ...drive your handler / client...
    assert fp.get_reports()[0]["success"] is True
```

Mirrors Java [`batch-worker-sdk-testkit`](../batch-worker-sdk-testkit/)
(`FakeBatchPlatform` + `@BatchWorkerTest`). Helpers shipped: 
`FakeBatchPlatform`, `make_test_context`, `make_test_config`,
`RecordingHandler`. Install via `pip install batch-worker-sdk[testkit]`
(`aiohttp` is testkit-only; production tenant workers don't pay for it).

## Examples (P5)

[`examples/sample-tenant-worker-python/`](../examples/sample-tenant-worker-python/)
is the Python counterpart to the Java `sample-tenant-worker/`. Two
demo handlers (`sample-echo`, `sample-sleep`) registered via
`@batch_task`. Forward-compatible with Lane T — runs in
"registration smoke" mode until `BatchPlatformClient` lands.

## Contributing

Contribution guide is shared with the parent repo —
see [`CONTRIBUTING.md`](../CONTRIBUTING.md). For SDK-specific
conventions (async style, error taxonomy, fixture promotion), full
guidelines land in P1 once the public API stabilizes.

## License

Apache-2.0, same as the rest of the repo. See [`LICENSE`](../LICENSE).
