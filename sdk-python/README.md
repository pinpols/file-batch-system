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
| **P4** ✅ (this PR) | `CancellationSignal` (asyncio.Event) + `ProgressReporter` (sensitive-key guard) + `stop_with_timeout` phased shutdown | done (Lane U) |
| **P5** | Testkit (FakeBatchPlatform / `@batch_task` decorator), examples, PyPI publish | Lane V |

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

### Cancellation + Progress (Lane U / P4)

Long-running handlers cooperate with the platform's cancel signal and
publish progress checkpoints through the per-task `SdkTaskContext`:

```python
from batch_worker_sdk import SdkTaskContext

async def execute(ctx: SdkTaskContext) -> None:
    for row in stream_rows():
        if ctx.cancel_signal and ctx.cancel_signal.is_cancellation_requested:
            return                                    # exit early, lease-scheduler asked us to stop
        await process(row)
        if ctx.progress_reporter and row.idx % 1000 == 0:
            ctx.progress_reporter.report(
                {"processed": row.idx, "total": row.total, "checkpoint": row.cursor}
            )
```

Sensitive keys (`password` / `secret` / `token` / `credential` /
`apikey` / `privatekey` / `accesskey`) raise `ValueError` — credentials
must travel via `required_env`, never via progress payloads (Java SDK
Lane C parity).

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

## Contributing

Contribution guide is shared with the parent repo —
see [`CONTRIBUTING.md`](../CONTRIBUTING.md). For SDK-specific
conventions (async style, error taxonomy, fixture promotion), full
guidelines land in P1 once the public API stabilizes.

## License

Apache-2.0, same as the rest of the repo. See [`LICENSE`](../LICENSE).
