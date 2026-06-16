# examples — 扩展模板索引

本目录放**给业务方复制的可运行示范**。每个子目录是一个独立 module / project(**不挂主 reactor**),
演示一种扩展方式。复制改改即用,**不用 PR 回主仓**。

| 示范 | 演示的扩展模型 | 产出 | 部署到哪 |
|---|---|---|---|
| [`sftp-push-executor`](sftp-push-executor/) | **Task SPI 插件** | 一个 jar | 平台 worker 的 classpath |
| [`sample-tenant-worker-java`](sample-tenant-worker-java/) | **自托管 SDK worker · Java**(纯 main wiring)| 一个独立进程 | 租户自己的机房 |
| [`sample-tenant-worker-java-spring`](sample-tenant-worker-java-spring/) | **自托管 SDK worker · Java**(Spring Boot starter)| 一个独立进程 | 租户自己的机房 |
| [`sample-tenant-worker-python`](sample-tenant-worker-python/) | **自托管 SDK worker · Python**(`@batch_task` 装饰器 + asyncio)| 一个独立进程 | 租户自己的机房 |
| [`sample-tenant-worker-go`](sample-tenant-worker-go/) | **自托管 SDK worker · Go**(`sdk/go` 运行时 + kafka 适配器)| 一个独立进程 | 租户自己的机房 |
| [`sample-tenant-worker-typescript`](sample-tenant-worker-typescript/) | **自托管 SDK worker · TypeScript**(`sdk/typescript` 运行时 + kafkajs)| 一个独立进程 | 租户自己的机房 |
| [`sample-tenant-worker-rust`](sample-tenant-worker-rust/) | **自托管 SDK worker · Rust**(`sdk/rust` + rdkafka;示意·待 reqwest 适配器)| 一个独立进程 | 租户自己的机房 |
| [`batch-worker-sdk-template`](batch-worker-sdk-template/) | **自托管 SDK 生产 template**(Java + Dockerfile + CI)| fork 起点 | 租户 fork 后部署 |

> **「示范」vs「template」**:`sample-tenant-worker*` 是**教学样例**(尽量短,纯演示 API 用法);
> `batch-worker-sdk-template` 是**生产起点**(含 Dockerfile / `.env.example` / `run.sh` / GitHub Actions CI),
> 租户直接 fork 改改 handler 即可起容器。
>
> `sample-tenant-worker-{java/java-spring/python/go/typescript/rust}` 演示**同一自托管能力的多语言接入**,按租户技术栈选:
> - **`sample-tenant-worker-java`**(纯 Java):租户用 Java 但不要 Spring(短启动 / Lambda / FaaS)
> - **`sample-tenant-worker-java-spring`**(Java + Spring):租户已用 Spring Boot,要自动装配
> - **`sample-tenant-worker-python`**(Python 3.12+ asyncio):租户主语言是 Python,handler 用 async def
> - **`sample-tenant-worker-go`**(Go):`sdk/go` 运行时 + segmentio/kafka-go 适配器
> - **`sample-tenant-worker-typescript`**(Node ≥25):`sdk/typescript` 运行时 + kafkajs
> - **`sample-tenant-worker-rust`**(Rust):`sdk/rust` + rdkafka — 示意,真 HTTP 待 reqwest 适配器
>
> core SDK 始终 framework-free(Java SDK Spring-free / Python SDK 无 Django/FastAPI 依赖),Spring 仅是 Java 侧可选 starter。命名统一 `sample-tenant-worker-<lang>`。

---

## 两种扩展模型的区别

两者**服务两类完全不同的诉求**,不是二选一,可以同时存在。

| 维度 | `sftp-push-executor`(Task SPI 插件) | `sample-tenant-worker*` / template(自托管 SDK) |
|---|---|---|
| 来源 | ADR-029 / [`task-spi-design.md`](../docs/design/task-spi-design.md) | ADR-035(SDK 自托管 = 多租隔离 Phase B) |
| **解决的问题** | 平台内**新增一种 taskType**(如 sftp 推送),不改主仓 | worker **跑在租户侧**,数据/凭据 0 出域 |
| 形态 | 一个 **jar 包** | 一个**完整独立进程**(自带 `main()` / `python -m`) |
| 跑在哪 | **平台自己的 worker 进程**里 | **租户自己的基础设施** |
| 实现接口 | `BatchTaskExecutor`(taskType / capability / execute) | `SdkTaskHandler`(Java)/ `@batch_task` async def(Python) |
| 注册机制 | JDK `ServiceLoader`(`META-INF/services/...BatchTaskExecutor`)自动发现 | SDK 内注册,经 Kafka 收派发 |
| 依赖 | 唯一硬依赖 `batch-common`(`provided`),不引 framework | Java:只依赖 `batch-worker-sdk`(**禁** Spring/batch-common/worker-core);Python:`batch-worker-sdk` PyPI 包 |
| 谁来部署 | 平台运维(把 jar 丢进 worker classpath) | 租户团队(在自家 K8s/VM 跑进程) |
| 数据可达性 | worker 持平台凭据,能访问平台数据源 | **平台访问不到租户数据** |
| 成本归属 | 平台账单 | 租户账单 |
| 多语言 | 仅 Java | Java / Python / Go / TypeScript / Rust 均有 sample-tenant-worker-<lang>(见 [BYO SDK 指南](../docs/sdk/byo-sdk-guide.md)) |

**一句话区别:**

- **Task SPI 插件** = "给平台 worker 装个新能力插件" —— worker 还是平台的,只是多识别一个
  `taskType`(如 `sftp_push`)。适合:平台想快速支持一种原子任务(shell / SQL / HTTP / SFTP /
  存过),又不想为它开一个新 worker module。
- **自托管 SDK** = "把整个 worker 进程搬到租户那边" —— 平台只当调度面。适合:数据驻留 / 合规
  ("代码必须在我家执行")/ 跨语言(Python ETL / Go 服务集成)/ 租户专属运行时(特殊 JDBC 驱动、native 依赖)。

> 深入设计:Task SPI 双层模型见 [`docs/design/task-spi-design.md`](../docs/design/task-spi-design.md);
> 自托管 SDK 定位见 [`ADR-035`](../docs/architecture/adr/ADR-035-tenant-self-hosted-worker-sdk.md);
> 原子任务 worker 的特权隔离见 [`ADR-029`](../docs/architecture/adr/ADR-029-dedicated-spi-worker.md);
> 协议契约权威源(任何语言 SDK 必须实装):[`docs/sdk/wire-protocol.md`](../docs/sdk/wire-protocol.md) +
> [`docs/api/orchestrator-internal.openapi.yaml`](../docs/api/orchestrator-internal.openapi.yaml) +
> [`docs/api/sdk-contract-fixtures/`](../docs/api/sdk-contract-fixtures/) 12 个 JSON 用例。

---

## 怎么选?

```
我想加新 taskType?
├─ 一次性 / 平台运维管理     → sftp-push-executor 模式(SPI 插件)
└─ 跑在我自己机房 / 数据要驻留 → sample-tenant-worker* 模式(自托管 SDK)
     ├─ 我是 Java 玩家
     │   ├─ 想 starter 自动装配  → sample-tenant-worker-java-spring
     │   ├─ 要短启动 / 无 Spring → sample-tenant-worker-java(纯 main)
     │   └─ 直接生产起步 fork    → batch-worker-sdk-template
     ├─ 我是 Python / 数据团队  → sample-tenant-worker-python
     ├─ 我是 Go 玩家            → sample-tenant-worker-go
     ├─ 我是 Node/TS 玩家       → sample-tenant-worker-typescript
     ├─ 我是 Rust 玩家          → sample-tenant-worker-rust(示意,待 reqwest)
     └─ 其它语言                → 自研 SDK,跑通
                                    docs/api/sdk-contract-fixtures/ 12 个 case
                                    见 docs/sdk/byo-sdk-guide.md
```

---

## 维度提醒:跟 Phase D 不是一回事

多租户隔离里还有个 **Phase D(per-tenant worker pool)**:worker **仍在平台侧**,只是按租户拆成
独立 deployment / topic / 凭据做物理隔离(见
[`docs/runbook/per-tenant-worker-onboarding.md`](../docs/runbook/per-tenant-worker-onboarding.md))。

- Task SPI 插件 = **扩能力**(平台 worker 多认一个 taskType)
- 自托管 SDK = **挪位置**(worker 进程搬到租户,可选语言)
- Phase D = **拆隔离**(平台 worker 按租户分池)

三个维度正交,别混。

---

## 关联 SDK 文档(自托管 SDK 接入路径)

按顺序读:

1. [`docs/sdk/quickstart.md`](../docs/sdk/quickstart.md) — 5 分钟起跑
2. [`docs/sdk/onboarding-journey.md`](../docs/sdk/onboarding-journey.md) — 从 0 到第一个 task 跑通的完整 checklist
3. [`docs/sdk/wire-protocol.md`](../docs/sdk/wire-protocol.md) — 通讯协议 + 故障感知矩阵
4. [`docs/sdk/byo-sdk-guide.md`](../docs/sdk/byo-sdk-guide.md) — Bring Your Own SDK(写非 Java/Python 实现)
5. [`docs/sdk/troubleshooting.md`](../docs/sdk/troubleshooting.md) — 排障速查
