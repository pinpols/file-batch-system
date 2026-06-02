# sample-tenant-worker-python — ADR-035 自托管 worker(Python)示范

Python 版自托管 worker 最小示范,集成
[`batch-worker-sdk`(Python)](../../batch-worker-sdk-python/),用 `@batch_task` 装饰器注册 handler。
**Python 3.12+,async-only**。

> **同一自托管能力的 4 种接入,按租户技术栈选**:
> - [`../sample-tenant-worker`](../sample-tenant-worker/) — 纯 Java + 手写 `main` wiring
> - [`../sample-tenant-worker-spring`](../sample-tenant-worker-spring/) — Java + Spring Boot starter
> - **`sample-tenant-worker-python`(本目录)** — Python + asyncio
> - [`../batch-worker-sdk-template`](../batch-worker-sdk-template/) — Java 生产 fork 起点(Dockerfile + CI 全套)
>
> 其它语言(Go / Rust / Node)请走 [BYO SDK guide](../../docs/sdk/byo-sdk-guide.md) 自研,跑通
> [`docs/api/sdk-contract-fixtures/`](../../docs/api/sdk-contract-fixtures/) 的 12 个契约即可。

## Quickstart(5 分钟)

```bash
# 1. SDK editable install(在 repo root 跑)
pip install -e sdk-python

# 2. 本 sample editable install
pip install -e examples/sample-tenant-worker-python

# 3. 设环境变量(用 BATCH_SDK_* 前缀对齐 BatchPlatformClientConfig.from_env())
export BATCH_SDK_BASE_URL=https://batch.example.com
export BATCH_SDK_TENANT_ID=tenant-xyz
export BATCH_SDK_WORKER_CODE=xyz-sample-py-1
export BATCH_SDK_KAFKA_BOOTSTRAP=kafka.example.com:9092
export BATCH_SDK_API_KEY=...

# 4. 跑(模块入口,自动 collect_registered_handlers)
python -m sample_tenant_worker
```

> **env 前缀提醒**:Java sample 用 `BATCH_*`(自己写的 `requireEnv`),Python `BatchPlatformClientConfig.from_env()` 默认 `BATCH_SDK_*`,**两套不能混**。
> 见 [`docs/sdk/troubleshooting.md`](../../docs/sdk/troubleshooting.md) §1。

## 注册的 handler

| `task_type` | 行为 |
| --- | --- |
| `sample-echo` | 把 `parameters` 原样回吐,演示最小契约 |
| `sample-sleep` | 按 `parameters.millis` async sleep,演示长任务 + lease 自动续约 |

handler 通过模块顶层 `@batch_task` 装饰器在 import 时**自动注册**,
入口走 `collect_registered_handlers()` 收集,无需手写 `register_handler()`。

## descriptor() 端到端(对齐 Java sample SDK Phase 3 M3.1)

handler 可重写 `descriptor()` 声明 taskType 元数据,链路同 Java:

```python
from batch_worker_sdk import SdkTaskTypeDescriptor, batch_task

@batch_task("sample-import-py")
class ImportEchoHandler:
    async def execute(self, ctx) -> SdkTaskResult:
        ...

    def descriptor(self) -> SdkTaskTypeDescriptor:
        return SdkTaskTypeDescriptor(
            task_type="sample-import-py",
            display_name="示范导入(Python)",
            input_schema={"type": "object", "required": ["sourcePath"]},
            parameters={"batchSize": 2, "targetTable": "staging_${bizDate}"},
        )
```

声明后 register 上报、console 查询、派单合并的链路与 Java sample 完全一致,见
[`../sample-tenant-worker/README.md#descriptor-端到端`](../sample-tenant-worker/README.md#descriptor-端到端)。

> **凭据纪律(强制)**:SDK 在 `register` 上报 descriptor 时 BE 端的
> `SensitiveDataValidator` 会拦截含 `password / secret / token / apikey / credential` 等关键字的字段。
> 凭据一律走 env,**不要塞 descriptor / parameters**。

## 本地不起 orchestrator 测试 — 用 `FakeBatchPlatform`

```python
import asyncio
from batch_worker_sdk import BatchPlatformClient, BatchPlatformClientConfig
from batch_worker_sdk.testkit import FakeBatchPlatform
from sample_tenant_worker import echo  # @batch_task 装饰的 handler

async def main():
    async with FakeBatchPlatform() as fp:
        cfg = BatchPlatformClientConfig(
            base_url=fp.base_url,
            tenant_id="test",
            worker_code="test-worker",
            api_key="dummy",
        )
        client = BatchPlatformClient(cfg)
        client.register_handler(echo)
        await client.start()
        await fp.dispatch_task({"taskId": 1, "tenantId": "test", "taskType": "sample-echo", "parameters": {"hello": "world"}})
        reports = fp.get_reports()
        assert reports[0]["taskId"] == 1
        await client.stop(timeout=5.0)

asyncio.run(main())
```

完整 testkit 用法见 [`batch-worker-sdk-python/tests/testkit/`](../../batch-worker-sdk-python/tests/testkit/)。

## Java sample 等价对照(快速 mental map)

| Java 端(`sample-tenant-worker`) | Python 端(本目录) |
|---|---|
| `class EchoHandler implements SdkTaskHandler` | `@batch_task("sample-echo")` 装饰的 `async def echo(ctx)` |
| `client.register(new EchoHandler())` | `for h in collect_registered_handlers(): client.register_handler(h)` |
| `BatchPlatformClientConfig.builder()...build()` | `BatchPlatformClientConfig.from_env()` 或直接构造 pydantic model |
| `client.start()` + JVM shutdown hook | `await client.start()`;`finally: await client.stop(timeout=30)` |
| `BatchPlatformClient.stop(Duration)` | `await client.stop(timeout=30)`(秒 / `timedelta`)|
| handler 抛异常 → 转 `SdkTaskResult.fail(...)` | 同 |

## 业务方落地

复制本目录到自己 repo,改:

1. `pyproject.toml` 的 `name` / 依赖锁定
2. `src/sample_tenant_worker/main.py` 里的 config 来源(从你自家配置中心读 / Vault / K8s Secret)
3. 把 `echo` / `sleep` 换成业务 handler

**不要引入** Django / FastAPI / Flask — SDK 设计就是为了不把这些拉到租户进程里。需要 web
端点可以单独跑一个 HTTP server 旁路。

## 关联文档

- [`docs/sdk/quickstart.md`](../../docs/sdk/quickstart.md) — 5 分钟 quickstart
- [`docs/sdk/wire-protocol.md`](../../docs/sdk/wire-protocol.md) — 通讯协议(任何语言 SDK 必须实装)
- [`docs/sdk/onboarding-journey.md`](../../docs/sdk/onboarding-journey.md) — 从 0 到第一个 task 完整 checklist
- [`docs/sdk/troubleshooting.md`](../../docs/sdk/troubleshooting.md) — 排障
- [`batch-worker-sdk-python/README.md`](../../batch-worker-sdk-python/README.md) — Python SDK 全 Roadmap + Public API
