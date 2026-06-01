# examples — 扩展模板索引

本目录放**给业务方复制的可运行示范**。每个子目录是一个独立 Maven module(**不挂主 reactor**),
演示一种扩展方式。复制改改即用,**不用 PR 回主仓**。

| 示范 | 演示的扩展模型 | 产出 | 部署到哪 |
|---|---|---|---|
| [`sftp-push-executor`](sftp-push-executor/) | **Task SPI 插件** | 一个 jar | 平台 worker 的 classpath |
| [`sample-tenant-worker`](sample-tenant-worker/) | **自托管 SDK worker(纯 Java)** | 一个独立进程 | 租户自己的机房 |
| [`sample-tenant-worker-spring`](sample-tenant-worker-spring/) | **自托管 SDK worker(Spring Boot starter)** | 一个独立进程 | 租户自己的机房 |

> `sample-tenant-worker` 与 `sample-tenant-worker-spring` 是**同一自托管能力的两种接入**(手写 `main` wiring vs Spring 自动装配),
> 按租户技术栈二选一,对比见 [Spring 示例 README](sample-tenant-worker-spring/README.md#两种接入对比)。core SDK 始终 Spring-free,Spring 仅是可选 starter。

---

## 两种扩展模型的区别

两者**服务两类完全不同的诉求**,不是二选一,可以同时存在。

| 维度 | `sftp-push-executor`(Task SPI 插件) | `sample-tenant-worker`(自托管 SDK) |
|---|---|---|
| 来源 | ADR-029 / [`task-spi-design.md`](../docs/design/task-spi-design.md) | ADR-035(SDK 自托管 = 多租隔离 Phase B) |
| **解决的问题** | 平台内**新增一种 taskType**(如 sftp 推送),不改主仓 | worker **跑在租户侧**,数据/凭据 0 出域 |
| 形态 | 一个 **jar 包** | 一个**完整独立进程**(自带 `main()`) |
| 跑在哪 | **平台自己的 worker 进程**里 | **租户自己的基础设施** |
| 实现接口 | `BatchTaskExecutor`(taskType / capability / execute) | `SdkTaskHandler` |
| 注册机制 | JDK `ServiceLoader`(`META-INF/services/...BatchTaskExecutor`)自动发现 | SDK 内注册,经 Kafka 收派发 |
| 依赖 | 唯一硬依赖 `batch-common`(`provided`),不引 framework | 只依赖 `batch-worker-sdk`,**禁** Spring / batch-common / worker-core |
| 谁来部署 | 平台运维(把 jar 丢进 worker classpath) | 租户团队(在自家 K8s/VM 跑进程) |
| 数据可达性 | worker 持平台凭据,能访问平台数据源 | **平台访问不到租户数据** |
| 成本归属 | 平台账单 | 租户账单 |

**一句话区别:**

- **Task SPI 插件** = "给平台 worker 装个新能力插件" —— worker 还是平台的,只是多识别一个
  `taskType`(如 `sftp_push`)。适合:平台想快速支持一种原子任务(shell / SQL / HTTP / SFTP /
  存过),又不想为它开一个新 worker module。
- **自托管 SDK** = "把整个 worker 进程搬到租户那边" —— 平台只当调度面。适合:数据驻留 / 合规
  ("代码必须在我家执行")/ 跨语言 / 租户专属运行时(特殊 JDBC 驱动、native 依赖)。

> 深入设计:Task SPI 双层模型见 [`docs/design/task-spi-design.md`](../docs/design/task-spi-design.md);
> 自托管 SDK 定位见 [`ADR-035`](../docs/architecture/adr/ADR-035-tenant-self-hosted-worker-sdk.md);
> 原子任务 worker 的特权隔离见 [`ADR-029`](../docs/architecture/adr/ADR-029-dedicated-spi-worker.md)。

---

## 维度提醒:跟 Phase D 不是一回事

多租户隔离里还有个 **Phase D(per-tenant worker pool)**:worker **仍在平台侧**,只是按租户拆成
独立 deployment / topic / 凭据做物理隔离(见
[`docs/runbook/per-tenant-worker-onboarding.md`](../docs/runbook/per-tenant-worker-onboarding.md))。

- Task SPI 插件 = **扩能力**(平台 worker 多认一个 taskType)
- 自托管 SDK = **挪位置**(worker 进程搬到租户)
- Phase D = **拆隔离**(平台 worker 按租户分池)

三个维度正交,别混。
