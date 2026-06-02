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
| **P2** ✅ | Kafka consumer (aiokafka), `TaskDispatcher` (CLAIM/EXECUTE/REPORT), capacity-aware pause | done (Lane S) |
| **P3** ✅ | `BatchPlatformClient` + `HeartbeatScheduler` + `LeaseRenewalScheduler` + heartbeat-directive parsing | done (Lane T) |
| **P4** ✅ | Lifecycle: budgeted `stop(timeout)`, cancellation signal wiring, progress reporter, deactivate | done (Lane U) |
| **P5** ✅ (this PR) | Testkit (`FakeBatchPlatform`) + `@batch_task` decorator + `examples/sample-tenant-worker-python/` | done (Lane V) |

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
