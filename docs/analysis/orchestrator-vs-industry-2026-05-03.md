# Orchestrator vs 业界实现 — 缺陷盘点 2026-05-03

> **范围**：本仓 `batch-orchestrator` 模块的关键能力与业界主流批量/工作流调度系统对比，识别真缺陷与设计取舍。
> **方法**：grep 反证 + 文档交叉对照，不凭空 brainstorm。

---

## 1. 系统定位

本 orch 是 **状态表驱动 + 多模块协作 + multi-tenant + file pipeline 中心** 的批量调度，介于 DolphinScheduler 和 Temporal 之间，偏 DB-centric。

| 对比对象 | 关键特征 |
|---|---|
| **Temporal / Cadence** (Uber/AWS) | workflow-as-code + event sourcing + history replay + 横向扩展 history shards |
| **Apache Airflow** | Python DAG + web UI + executor 抽象 (Local/Celery/K8s) + scheduler-as-state-machine |
| **DolphinScheduler / XXL-Job** | Java DB-centric + web UI + Master/Worker (国内主流) |
| **Argo Workflows** | K8s 原生 YAML DAG + dynamic fan-out |
| **Spring Batch** | JVM 单机批处理 (本系统某种意义上是它的分布式版本) |

---

## 2. 真缺陷 (按严重度)

### 🔴 P0 — 核心可用性

#### 2.1 `timeout_seconds` 字段定义但无 enforcer

- **现状**：`job_definition.timeout_seconds` 列存在 (V4)，但 grep 全仓 0 命中周期性 timeout enforcer
- **问题**：task / partition / workflow_run 卡 RUNNING 超过 timeout 后**永远不会被 mark FAILED**
- **业界对比**：
  - Temporal: activity-level + workflow-level timeout (start-to-close, schedule-to-close, heartbeat)，自动 fail
  - Airflow: `dagrun_timeout` + `execution_timeout` 自动 mark failed
- **当前依靠**：`PartitionLeaseReclaimScheduler` 回收 lease 过期的 partition (但这是 worker 心跳丢失的兜底，不是业务 timeout)
- **影响**：业务声明的"30 分钟 timeout"不生效；失联 worker 跑 8 小时也不会被打断

#### 2.2 workflow_run 无独立 stuck recovery reconciler

- **现状**：仅有 `TriggerRequestLaunchReconciler` (launch 阶段) + `PartitionLeaseReclaimScheduler` (partition 阶段)
- **问题**：workflow_run 卡 RUNNING 状态靠 `DefaultTaskOutcomeService` 反向推动；如果 **task 全 stuck** 或 **partition 已 reclaim 但 workflow_run 没收到信号**，workflow_run 永远 RUNNING
- **业界对比**：
  - Airflow scheduler 周期性检测 `dagrun.state` + 重计算
  - Temporal 是 event-sourced 不存在这问题
- **影响**：长尾 stuck workflow 累积，需要人工 console 介入

### 🟡 P1 — 功能表达力

#### 2.3 `RetryPolicyType` 仅 NONE/FIXED/EXPONENTIAL,无 jitter / 任意函数

- **现状**：业务 retry 不暴露 jitter 参数 (Outbox 内部已有 jitter 实现，但未提升到通用层)
- **业界对比**：Temporal `RetryOptions.backoffCoefficient + maximumInterval + nonRetryableErrorTypes`；Resilience4j 可配任意 backoff function
- **影响**：大规模 retry 同步触发 (thundering herd)

#### 2.4 Sub-workflow 仅"父-子单层 ChildJob",不支持任意深度嵌套

- **现状**：`ChildJobLaunchSupport` 用 `_parentWorkflowRunId` 标记父，但 child 内部不能再嵌套 workflow
- **业界对比**：Temporal `Workflow.newChildWorkflowStub()` 任意层 nested + Airflow `SubDagOperator` (虽 deprecated 也支持)
- **影响**：复杂业务无法分层抽象，只能扁平堆 node
- **修复成本**：高 (核心模型 + 状态机改动) — 单独 ADR 立项

#### 2.5 无 dynamic DAG / runtime fan-out

- **现状**：DAG 在 `workflow_definition` 配置时固定；运行时不能根据数据动态新增节点
- **业界对比**：Argo Workflows `withItems` / `withParam` 运行时展开；Temporal 是代码 driven 任意循环展开
- **影响**："对每个文件并行处理"这类场景必须 workflow 定义时穷举，或者用 partition 内部的 sharding 替代 (语义不同)
- **修复成本**：高 (schema + runtime 大改造) — 单独 ADR 立项

### 🟡 P1 — 观测性

#### 2.6 无分布式 tracing (OpenTelemetry / Jaeger)

- **现状**：仅 `trace_id` 字段透传 (V77/V78 i18n 三元组同时透传)，没有 span / parent-span / W3C trace context
- Micrometer + TimedAspect 有 metric 但**没有跨服务 trace 串联**
- **业界对比**：几乎所有现代调度系统都有 OpenTelemetry 集成 (Airflow / Temporal / Argo)
- **影响**：排查 "trigger → orchestrator → worker → ack" 全链路只能靠 grep `trace_id`，无 timeline 视图
- **修复成本**：中-高 (跨模块基础设施改动 + 各 listener/producer 加 instrumentation) — 单独 ADR 立项

#### 2.7 无内置 web UI

- **现状**：console-api 提供 REST，但无配套前端 (用户得自己实现/集成)
- **业界对比**：Airflow / DolphinScheduler / Temporal Web UI 都自带 — workflow 拓扑图、运行历史、人工干预入口
- **影响**：运维门槛高，只能 SQL/curl 排障
- **修复成本**：高 (独立前端项目) — 单独立项

### 🟢 P2 — 架构哲学差异 (设计取舍, 不算缺陷)

#### 2.8 状态表驱动 vs Event Sourcing

- 本系统把状态写表 (job_instance / workflow_run / job_partition / job_task)，CAS 推进
- Temporal 把所有状态变化作为 event 写 history，任意时点 replay 重建状态
- **取舍**：状态表更直观、SQL 友好；event sourcing 更可追溯、replay 容易、但学习曲线陡
- **当前选择对 batch 场景合理** (批处理状态简单且离散)，不算缺陷

#### 2.9 单 PG 主库

- 已被 `scalability-assessment.md` §4.1 标"红色硬伤" + Phase 3 路线图
- 不算"未发现的缺陷"，已知规划中

#### 2.10 无 workflow 版本管理 / 平滑升级

- `workflow_definition.version` 列存在但**只是配置版本号**，不支持 "in-flight workflow_run 仍按旧版执行 + 新 run 走新版"
- 业界对比：Temporal versioning 用 `Workflow.getVersion()` API 让代码适配多版本
- **取舍**：batch 场景跑完就完，不像 long-running workflow 需要 in-flight migration；**当前不必修**

---

## 3. 修复路线

### 已修 (本次 session, 2026-05-03)

| # | 项 | commit |
|---|---|---|
| 1 | `TimeoutEnforcerScheduler` — 周期扫 RUNNING 超期实例 → mark FAILED | (本次) |
| 2 | `WorkflowRunStuckReconciler` — 周期检测无下游推进的 RUNNING workflow_run | (本次) |
| 3 | `RetryPolicyType` 加 jitter 参数 + `BackoffCalculator` 工具 | (本次) |

### 留 backlog (单独 ADR / 立项)

| # | 项 | 估时 | 阻塞 |
|---|---|---|---|
| 4 | Sub-workflow 任意嵌套 | 2-3 周 | 核心模型 + 状态机改动，需新 ADR |
| 5 | Dynamic DAG / runtime fan-out | 2-3 周 | schema + runtime 改造，需新 ADR |
| 6 | OpenTelemetry / 分布式 tracing | 2-4 周 | 跨模块基础设施 + 所有 listener/producer 加 instrumentation |
| 7 | 内置 web UI | 月级 | 独立前端项目 |

### 不计划 (设计取舍)

- §2.8 状态表 vs event sourcing
- §2.9 单 PG (Phase 3 路线图)
- §2.10 workflow 版本管理 (batch 场景不必)

---

## 4. 结论

orch 在 **基础正确性 + 多租隔离 + outbox 一致性 + 调度机制** 几个维度做得扎实，**0 严重并发 bug**；缺陷集中在 **可用性兜底** (timeout / stuck) 和 **观测性 / 表达力** 两块。

短期 (本 session) 收掉 P0 + 1 个 P1 即可让系统离"生产级 batch 调度平台"更近一步；其余 P1 是产品演进方向，建议按 ADR-by-ADR 节奏推进。
