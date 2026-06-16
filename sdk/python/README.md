# batch-worker-sdk (Python)

**file-batch-system** worker 协议的 Python SDK —— Java 版
[`batch-worker-sdk/`](../batch-worker-sdk/) 的 async-only 对等实现。

> **状态(2026-06-03)**: Phase 0-5 全部交付。Public API 稳定(`BatchPlatformClient` / `@batch_task` / `FakeBatchPlatform`),12 个跨 SDK 契约 fixture 全过(Lane P drift guard);PyPI 待 1.0 发布。可用于内测与 staging 跑通,生产使用前请确认契约 fixture 对自家场景已覆盖。

## 状态

| 维度 | 状态 |
| --- | --- |
| 包结构 + 8 子包(`client / dispatcher / handler / internal / retry / scheduler / task / testkit`) | 完成 |
| 工具链 (ruff, mypy, pytest) | 完成 |
| CI (`.github/workflows/sdk-python.yml`) | 完成 |
| 契约 fixture runner(12 个 JSON) | 完成,与 Java SDK 跑同一份 fixture |
| `BatchPlatformClient` + `TaskDispatcher` + `KafkaTaskConsumer` + heartbeat/lease scheduler | 完成 |
| `@batch_task` 装饰器 + `collect_registered_handlers()` | 完成 |
| `testkit.FakeBatchPlatform` in-process 平台 fake | 完成 |
| PyPI 发布 | 待办(1.0 release 后) |

## 设计决策

- **Python 3.12+** —— 现代 typing(`type` 语句、PEP 695、泛型
  `TypeVar` 语法)不退让。
- **Async-only** —— handler 签名为 `async def handle(ctx) -> None`。
  没有同步 wrapper。需要同步请在 `asyncio.run` 内运行。
- **PyPI 名 `batch-worker-sdk`,import 名 `batch_worker_sdk`** ——
  与 Java 模块名对齐,按 PEP 8 把 `-` 换成 `_`。
- **版本 `0.0.1a0`** —— PEP 440 对 "0.0.1-alpha" 的规范化形式,
  hatchling 拒绝带连字符的写法。
- **同一个 monorepo** —— 与 Java SDK 并列在 `sdk-python/` 下,
  独立工具链;**不**纳入 Maven reactor。

## 安装(preview)

```bash
# 待 1.0 发布到 PyPI 后:
pip install batch-worker-sdk

# 在此之前,从源码 editable 安装(repo root 跑):
pip install -e sdk/python

# testkit 仅供测试,生产 worker 不要装 testkit extra:
pip install -e "sdk/python[testkit]"
```

## 快速接入(5 分钟)

```python
import asyncio
from batch_worker_sdk import (
    BatchPlatformClient,
    BatchPlatformClientConfig,
    SdkTaskContext,
    SdkTaskResult,
    batch_task,
    collect_registered_handlers,
)


@batch_task("tenant-xyz-import")
async def import_handler(ctx: SdkTaskContext) -> SdkTaskResult:
    rows = await do_import(ctx.parameters)
    return SdkTaskResult.success_with({"rows": rows})


async def main() -> None:
    cfg = BatchPlatformClientConfig.from_env()   # 读 BATCH_SDK_* 前缀
    client = BatchPlatformClient(cfg)
    for handler in collect_registered_handlers():
        client.register_handler(handler)
    await client.start()
    try:
        await asyncio.Event().wait()             # 主线程驻留,Kafka 派单驱动 handler
    finally:
        await client.stop(timeout=30)


if __name__ == "__main__":
    asyncio.run(main())
```

**env 前缀**: `BATCH_SDK_BASE_URL` / `BATCH_SDK_TENANT_ID` / `BATCH_SDK_WORKER_CODE` / `BATCH_SDK_API_KEY` / `BATCH_SDK_KAFKA_BOOTSTRAP` / `BATCH_SDK_KAFKA_TOPIC_PATTERN` / `BATCH_SDK_KAFKA_GROUP_ID` —— 跟 Java sample 的 `BATCH_*` 前缀**不一样**,见 [`docs/sdk/troubleshooting.md`](../docs/sdk/troubleshooting.md) §1。

完整可跑示范: [`examples/sample-tenant-worker-python/`](../examples/sample-tenant-worker-python/)。

## Roadmap

| Phase | 范围 | 预估工作量 |
| --- | --- | --- |
| **P0** ✅ | 脚手架:pyproject、ruff、mypy、pytest、CI、契约 stub | done |
| **P0.5** ✅ | 公共 API 表面 stubs(handler / context / result / state / progress / cancellation / descriptor),对齐 Java SDK | done (Lane R) |
| **P1** ✅ | `PlatformHttpClient` (httpx async) + 重试/退避 + `BatchPlatformClientConfig` | done (Lane Q) |
| **P2** ✅ | Kafka consumer (aiokafka)、`TaskDispatcher` (CLAIM/EXECUTE/REPORT)、capacity-aware pause、租户自检、临时 `run_worker` 入口 | done (Lane S) |
| **P3** ✅ | `BatchPlatformClient` + `HeartbeatScheduler` + `LeaseRenewalScheduler` + heartbeat-directive 解析 | done (Lane T) |
| **P4** ✅ | 生命周期:budgeted `stop(timeout)`、取消信号串联、progress reporter、deactivate | done (Lane U) |
| **P5** ✅ | Testkit (`FakeBatchPlatform`) + `@batch_task` decorator + `examples/sample-tenant-worker-python/` | done (Lane V) |

来自 Lane N 的契约 fixture 驱动 P1–P3 的绿条。每个从 `xfail` 翻成
`pass` 的 fixture 都算前进一步。

## 用法 (P3)

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
        await asyncio.Event().wait()  # 阻塞至 SIGTERM
    finally:
        await client.stop(timeout=30)

asyncio.run(main())
```

## 与 Java SDK 的对等性

Python SDK 在**线协议层面**与 Java 版**完全等价** —— 相同的 HTTP
endpoint、相同的 Kafka topic、相同的 payload 结构。各自 API 风格
向所在语言习惯靠拢:

| 关注点 | Java | Python(目标) |
| --- | --- | --- |
| 模块 | [`batch-worker-sdk/`](../batch-worker-sdk/) | `sdk-python/` |
| 入口类 | `WorkerClient` | `WorkerClient` |
| Handler 签名 | `void handle(HandlerContext ctx)` | `async def handle(ctx: HandlerContext) -> None` |
| Spring Boot 集成 | [`batch-worker-sdk-spring-boot-starter`](../batch-worker-sdk-spring-boot-starter/) | (无;用 FastAPI / 原生 async) |
| Testkit | [`batch-worker-sdk-testkit`](../batch-worker-sdk-testkit/) | TBD (Phase 4) |

### 公共 API 表面 ↔ Java SDK (P0.5)

下方 7 个类型与 Java 1:1 对应。命名遵循 PEP 8(snake_case 方法和
字段),语义对齐 Java 侧。P0.5 只发 stubs,实现在 P1–P5 落地。

| Java | Python | Java 路径 |
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

**临时 API**(P3 替换):`runner.run_worker(config, handlers)` 临时把 HTTP / Dispatcher / Kafka 三件套串起来跑,等 P3 `BatchPlatformClient` 落地后改成 thin shim。源文件顶部用 `# Provisional API — Will be superseded by BatchPlatformClient in P3` 标注。

**P2 简化 vs Java**(明列出来,P3 接续清单):

- 没有 CLAIM 5xx 重试 loop —— Python `_http.PlatformHttpClient` 在 wire-protocol §C 层做过 retry,我们这里只 surface `TransientError` 让 Kafka lease timeout 重投。
- 没有 `ThrottledLogger`(Java Lane J #2)—— PAUSED-state drop 日志降到 DEBUG 避免刷屏,不引外部依赖。
- 没有 MDC —— 结构化字段以 `logging.LogRecord.extra` 形式传给用户的 formatter。

权威文档(两个 SDK 都从这里读):

- [`docs/sdk/wire-protocol.md`](../docs/sdk/wire-protocol.md) —— HTTP +
  Kafka 协议,失败模式矩阵。
- [`docs/sdk/byo-sdk-guide.md`](../docs/sdk/byo-sdk-guide.md) ——
  自带 SDK(Bring-Your-Own-SDK)指南。
- [`docs/api/sdk-contract-fixtures/`](../../docs/api/sdk-contract-fixtures/) ——
  Lane N 的 JSON fixtures,**每一个** SDK 实现的权威一致性套件。

## 开发

```bash
cd sdk-python
pip install -e .[dev]

# Lint + types
ruff check src tests
ruff format --check src tests
mypy src

# 测试
pytest -v

# 契约 runner(Phase 0:列出 fixtures,全 xfail)
bash scripts/run-contract.sh
```

## `@batch_task` decorator (P5)

仿照 FastAPI / Spring starter 风格的声明式 handler 注册。一个
`async def` + 一个 decorator + 启动时一次
`collect_registered_handlers()` 调用:

```python
from batch_worker_sdk import batch_task, SdkTaskContext, SdkTaskResult, collect_registered_handlers

@batch_task("my-job")
async def my_handler(ctx: SdkTaskContext) -> SdkTaskResult:
    return SdkTaskResult.success_with({"hello": "world"})

# 启动时:
for handler in collect_registered_handlers():
    client.register_handler(handler)  # Lane T (P3)
```

同步函数和空 `task_type` 在 decorator 时即拒绝(fail-fast)。可选的
`descriptor=` 让你声明 `SdkTaskTypeDescriptor` 给 console 表单
渲染器使用;decorator 会校验它与 `task_type` 一致,杜绝
线协议错位。

## testkit (P5)

`batch_worker_sdk.testkit` 提供一个 in-process 的平台 fake,
让租户测试无需 Kafka / orchestrator:

```python
from batch_worker_sdk.testkit import FakeBatchPlatform, make_test_config

async with FakeBatchPlatform() as fp:
    cfg = make_test_config(base_url=fp.base_url)
    # ...驱动你的 handler / client...
    assert fp.get_reports()[0]["success"] is True
```

对标 Java 的 [`batch-worker-sdk-testkit`](../batch-worker-sdk-testkit/)
(`FakeBatchPlatform` + `@BatchWorkerTest`)。提供的辅助:
`FakeBatchPlatform`、`make_test_context`、`make_test_config`、
`RecordingHandler`。通过 `pip install batch-worker-sdk[testkit]`
安装(`aiohttp` 只在 testkit 中用,生产租户 worker 不引入它)。

## 示例 (P5)

[`examples/sample-tenant-worker-python/`](../examples/sample-tenant-worker-python/)
是 Java `sample-tenant-worker/` 的 Python 对等示例。两个演示
handler(`sample-echo`、`sample-sleep`)通过 `@batch_task` 注册。
与 Lane T 向前兼容 —— 在 `BatchPlatformClient` 落地前以
"注册 smoke" 模式运行。

## 贡献

贡献指南沿用主仓 —— 见 [`CONTRIBUTING.md`](../CONTRIBUTING.md)。
SDK 专属约定(async 风格、错误分类、fixture 提升)等 P1 公共 API
稳定后再补完整指南。

## License

Apache-2.0,与主仓一致。见 [`LICENSE`](../LICENSE)。
