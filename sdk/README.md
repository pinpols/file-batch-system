# batch-worker-sdk —— 租户自托管 Worker SDK 使用说明书

让租户在**自己的进程、机房、K8s 或 VM** 中运行业务 handler，只通过 **HTTP(`/internal/*`) + Kafka** 与平台通信。SDK **不连接平台数据库、不加载平台实现代码，业务数据不离开租户边界**；平台只承担调度面职责（register → dispatch → claim → execute → report）。设计背景与边界见 [ADR-035](../docs/architecture/adr/ADR-035-tenant-self-hosted-worker-sdk.md)。

> 本文是 SDK 总入口（选型、安装、运行、测试和文档导航）。各语言细节见对应子目录 README；完整接入流程见 [`docs/sdk/quickstart.md`](../docs/sdk/quickstart.md) 与 [`docs/runbook/per-tenant-worker-onboarding.md`](../docs/runbook/per-tenant-worker-onboarding.md)。

## 选语言

5 种语言行为对齐(同一套契约 fixture 跨语言验证,见 [§测试与验证](#测试与验证)):

| 语言 | 包 / 坐标 | 安装 | 子目录 |
|---|---|---|---|
| **Java** | `io.github.pinpols.batch:batch-worker-sdk` | Maven 坐标(+ 可选 Spring starter) | [`java/`](java/) |
| **Python** | `batch-worker-sdk`(import `batch_worker_sdk`) | `pip install batch-worker-sdk` | [`python/`](python/) |
| **Go** | `batch-worker-sdk-go` | `go get`(module path 见 README) | [`go/`](go/) |
| **TypeScript** | `@batch/worker-sdk` | `npm i @batch/worker-sdk` | [`typescript/`](typescript/) |
| **Rust** | `batch-worker-sdk` (crate) | `cargo add`(`http` / `kafka` feature 可选) | [`rust/`](rust/) |

> 发布名称以各语言工程的 manifest（`pyproject.toml` / `Cargo.toml` / `package.json` / `pom.xml`）为准；发布通道与正式包名见 [`docs/sdk/RELEASING.md`](../docs/sdk/RELEASING.md)。1.0 前多数通过源码或本地安装使用。

## 最低环境要求

权威源 = 各语言 manifest(构建文件),下表与之同步。**6 类运行时可消费制品**(testkit 是测试期工具,不计):

| # | 制品 | 最低运行时 | 运行依赖 | 备注 |
|---|---|---|---|---|
| 1 | **Java core**(`batch-worker-sdk`) | **JDK 21+** | `jackson-databind` / `kafka-clients` / `slf4j-api`(`lombok` 仅 provided 编译期) | jar 编译到 bytecode 21(`maven.compiler.release=21`,不随平台 25),租户在 LTS 上即可跑 |
| 2 | **Java Spring starter**(`-spring-boot-starter`) | **JDK 21+** + 租户自带 **Spring Boot 3.x/4.x** | core + `spring-boot-autoconfigure`(版本随租户 SB,不锁定) | 仅 Spring 租户需要;非 Spring 直接用 core |
| 3 | **Python**(`batch-worker-sdk`) | **3.12+** | `httpx>=0.27` / `pydantic` / `aiokafka` | async-only(现代 typing:`type` 语句 / PEP 695) |
| 4 | **Go**(`batch-worker-sdk-go`) | **Go 1.26+** | 核心零依赖;Kafka 适配器 = 独立 nested module(`segmentio/kafka-go`) | CI 覆盖 Go 1.26/1.27；不用 Kafka 则不引该依赖 |
| 5 | **TypeScript**(`@batch/worker-sdk`) | **Node 22 或 24** | 核心零依赖;Kafka = 可选 `kafkajs`(`optionalDependencies`) | `engines` 仅允许两条 LTS major；CI 双版本验证 |
| 6 | **Rust**(`batch-worker-sdk` crate) | **核心 Rust 1.75+** | 核心零依赖;`http`(reqwest+rustls)/ `kafka`(rdkafka)= 可选 feature | 锁定依赖下适配器需 Rust 1.88+；默认 feature 全关 = std-only |

**通用前置(所有语言)**:
- 网络可达平台：HTTP `/internal/*` + Kafka broker（`batch.task.dispatch.<tenant>.*`）
- 平台签发的 **API key**（放入 HTTP header，业务数据不出租户边界）
- 时区/编码按平台约定(UTF-8;时间统一 UTC 传输)

## 两种形态(都在每个语言里)

1. **decision core** —— 纯函数、无 IO，将 wire 输入（HTTP 状态、心跳指令、续租、背压、停止信号）映射为结构化决策。跨语言 1:1 对齐，并由契约 fixture 固化（`then.expect` 词表）。
2. **runtime engine** —— 真实 transport（HTTP）、Kafka 消费、claim/execute/report 回路、心跳/租约调度和优雅停机。

此外 **Java / Python 提供内置 handler**：5 类 worker 的内建或抽象 handler（import / export / process / dispatch / atomic），可直接复用；见 [§5 类 worker handler](#5-类-worker-handler)。Go / TS / Rust 提供 decision core + runtime engine，业务 handler 由租户实现。

## 5 分钟接入

完整「最短可运行路径」见 [`docs/sdk/quickstart.md`](../docs/sdk/quickstart.md)。三步骨架(各语言一致):

1. **实现 handler** —— 输入 `SdkTaskContext`(tenant / taskId / taskType / parameters),输出 `SdkTaskResult`(success/failure + resultSummary)。
2. **配 client** —— 推荐 `from_env()` / `fromEnv()` 吃环境变量,默认前缀 **`BATCH_SDK_`**:`BATCH_SDK_BASE_URL` / `BATCH_SDK_TENANT_ID` / `BATCH_SDK_API_KEY` / `BATCH_SDK_WORKER_CODE` / `BATCH_SDK_KAFKA_BOOTSTRAP`。(sample 工程多用 `BATCH_*` 短前缀,两种皆可。)
3. **注册并 start** —— register 上报 `workerGroup=sdk-self-hosted`,订阅 node-direct 派单 topic(`batch.task.dispatch.<workerType>.node.<workerCode>`,对齐内建 worker),进入消费回路。

平台会下发给你:`BASE_URL` / `TENANT_ID` / `API_KEY`(scope `worker.execute`)/ Kafka bootstrap + topic pattern + group + SASL/SCRAM 凭据。`workerCode` 自取、租户内唯一。

可参考 [`examples/self-hosted-sdk/`](../examples/self-hosted-sdk/) 下对应语言的 sample 工程起步。

## 5 类 worker handler

Java / Python SDK 提供 5 类 worker 的内建或抽象 handler：

| 类型 | 用途 | 内建实现 |
|---|---|---|
| import | 文件导入 | FileImport(csv/jsonl/delimited) |
| export | 数据导出 | QueryExport |
| process | 数据加工 | 抽象基类 + typed |
| dispatch | 分发 | HttpDispatch |
| atomic | 原子任务 | Shell / Sql / StoredProc / Http |

租户可以继承抽象基类，也可以直接使用 typed handler。Go / TS / Rust 不提供内置业务 handler，需由租户实现。

## 测试与验证

Python 仓库开发与 CI 按 `sdk/python/uv.lock` 使用 `uv sync --locked`；CI 同时验证最低支持版本与当前稳定版本。其他语言使用各自的 manifest/lockfile，并由 [`docs/architecture/runtime-compatibility-contract-2026-09-01.md`](../docs/architecture/runtime-compatibility-contract-2026-09-01.md) 集中记录运行边界。

| 范围 | 入口 | 说明 |
|---|---|---|
| 5 类 handler batteries 单测(Java + Python) | `bash scripts/local/sdk-handler-tests.sh [java\|python\|all]` | 环境变量可覆盖;对 stale skip 显式告警 |
| 跨语言契约 fixture(决策核) | 各语言 test(`go test ./...` / `npm test` / `cargo test` / `pytest tests/contract` / Java surefire) | 同一份 [`docs/api/sdk-contract-fixtures/`](../docs/api/sdk-contract-fixtures/) 跨 5 语言跑,防漂移 |
| 真实 orchestrator 本地全链路 | `bash scripts/local/sdk-e2e-local.sh <go\|python\|typescript\|java\|rust>` | 真实依赖栈启动后，逐阶段断言 register→dispatch→claim→execute→report→terminal；见 [`docs/sdk/local-e2e-coverage.md`](../docs/sdk/local-e2e-coverage.md) |
| 租户 handler 单测 | testkit `FakeBatchPlatform` / `@BatchWorkerTest`（Java 见 [`java/testkit/`](java/testkit/)，Python `batch_worker_sdk.testkit`） | 进程内平台替身，不连接真实依赖栈 |

## 目录结构

```
sdk/
  java/        Java SDK 家族(core / spring starter / testkit)—— 见 java/README.md
  python/      Python SDK(async-only,3.12+)
  go/          Go SDK(decision core + runtime + conformance)
  typescript/  TypeScript SDK(@batch/worker-sdk)
  rust/        Rust SDK(decision core + runtime,http/kafka feature 可选)
```

样例工程在 [`examples/self-hosted-sdk/`](../examples/self-hosted-sdk/);文档在 [`docs/sdk/`](../docs/sdk/)。

## 文档索引

完整列表见 [`docs/sdk/README.md`](../docs/sdk/README.md)。常用:

- [quickstart.md](../docs/sdk/quickstart.md) —— 5 分钟启动示例
- [byo-sdk-guide.md](../docs/sdk/byo-sdk-guide.md) —— BYO 完整指南
- [wire-protocol.md](../docs/sdk/wire-protocol.md) —— 通信协议权威源
- [byo-conformance-contract.md](../docs/sdk/byo-conformance-contract.md) —— 防漂移契约
- [sdk-parity-matrix.md](../docs/sdk/sdk-parity-matrix.md) —— 跨语言能力对齐矩阵
- [troubleshooting.md](../docs/sdk/troubleshooting.md) —— 排障
- [RELEASING.md](../docs/sdk/RELEASING.md) —— 发布管线与真实包名
