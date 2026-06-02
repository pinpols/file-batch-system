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
| **P0** | Scaffolding: pyproject, ruff, mypy, pytest, CI, contract stub | 1d |
| **P0.5** (this PR) | Public API surface stubs (handler / context / result / state / progress / cancellation / descriptor) mirroring Java SDK | 0.5d |
| **P1** | `WorkerClient` (httpx async), `HandlerContext`, `WorkerConfig`, register/heartbeat | 3-4d |
| **P2** | Kafka consumer (aiokafka), task dispatch loop, graceful shutdown | 3-4d |
| **P3** | Scheduler (APScheduler or custom), retry / backoff (tenacity), DLQ | 2-3d |
| **P4** | OpenTelemetry tracing + metrics, structured logging, fingerprint endpoint | 2d |
| **P5** | PyPI release pipeline, version bump automation, quickstart docs | 1-2d |

Contract fixtures from Lane N drive the green-bar for P1–P3. Every
fixture that flips from `xfail` to `pass` is forward progress.

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

## Contributing

Contribution guide is shared with the parent repo —
see [`CONTRIBUTING.md`](../CONTRIBUTING.md). For SDK-specific
conventions (async style, error taxonomy, fixture promotion), full
guidelines land in P1 once the public API stabilizes.

## License

Apache-2.0, same as the rest of the repo. See [`LICENSE`](../LICENSE).
