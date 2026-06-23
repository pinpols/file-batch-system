# 压测维度与场景对照

本文定义 batch-platform **负载/容量**视角下的观测维度，并与 `load-tests` 模块中的 Gatling 场景一一对应。  
编排细节与指标释义以运维 Runbook、Prometheus 规则为准；此处只界定「压测测了什么、没测什么」。

---

## 1. 维度矩阵

| 维度 | 含义 | 原有场景是否覆盖 | 补全后场景 |
|------|------|------------------|------------|
| **触发受理** | `batch-trigger` 对 launch 请求的吞吐与延迟 | `JobLaunchSimulation`、`CapacityBaselineSimulation`（写分支） | 不变 |
| **控制台读** | 控制台列表类查询在并发下的延迟 | `ConsoleQuerySimulation`、`CapacityBaselineSimulation`（读分支） | 不变 |
| **调度可观测性** | 加压写入的同时，orchestrator 侧调度快照类只读接口仍可快速响应（间接反映调度路径繁忙程度，**不**等价于「派发算法正确性」） | 否 | `SchedulingSnapshotUnderLoadSimulation` |
| **调度积压 / 派发滞后** | 固定 launch RPS 下，观测 `job_partition/job_task/job_instance` 的 WAITING / READY / RUNNING 是否堆积，判断 waiting dispatch 是否追得上 | 否 | `SchedulingBacklogUnderLoadSimulation` + `scripts/sample-scheduler-backlog.sh` |
| **执行闭环（端到尾）** | 从 launch 受理到实例在控制台可见为**业务终态**的耗时分布（依赖真实 Worker 推进 CLAIM→执行→REPORT） | 否 | `LaunchPipelineCompletionSimulation` |
| **执行生命周期接口** | Worker 与 orchestrator 的 `CLAIM → REPORT` 内部接口吞吐和错误率 | 否 | `WorkerTaskLifecycleSimulation`（显式 CSV task feeder，仅隔离任务） |
| **任务处理能力 / 积压** | 单位时间业务完成量、分区或队列 backlog | HTTP 压测**无法单独**给出「每秒业务任务完成数」；需结合 DB/Prometheus/Grafana 或在报告中引用稳态窗口内的完成采样 | `sample-scheduler-backlog.sh` 用 `ji_success/jt_success` 增量计算完成吞吐；`jp_waiting/jt_ready` 斜率判断积压 |

---

## 2. 场景说明（补全项）

### 2.1 `SchedulingSnapshotUnderLoadSimulation`

- **目的**：在持续发起 launch 的前提下，并行压 orchestrator **`GET /internal/scheduler/snapshot`**（与 trigger launch 相同的密钥头）。
- **验收关注点**：快照接口延迟（默认断言写路径 p95、读路径 p99 与全局错误率），用于观察「写压背景下调度侧只读路径是否恶化」。
- **非目标**：不断言快照内字段的业务正确性；不替代调度单元测试或 E2E。
- **前置**：目标环境 orchestrator 端口与密钥与配置一致；若关闭安全旁路，必须提供正确的 `X-Internal-Secret`。

### 2.2 `LaunchPipelineCompletionSimulation`

- **目的**：单次虚拟用户路径：**launch → 轮询控制台 batch-status**，直到实例状态落入业务终态集或超出最大轮询次数。
- **验收关注点**：Gatling **`pipeline_completion` 分组**统计端到尾耗时；全局仍校验 launch 与轮询 HTTP 错误率。
- **非目标**：不代替 Worker 单测；若无 Worker 或 Job 无法推进，轮询可能直至超时，报告需在分析时剔除此类样本。
- **前置**：与既有 launch 压测相同（租户、`jobCode`、bizDate、种子数据）；控制台 Bearer Token；**需有 Worker 消费任务**才有意义的端到尾样本。

### 2.3 `SchedulingBacklogUnderLoadSimulation`

- **目的**：在固定 `scheduling.launch.rps` 下持续写入，同时读取调度快照、scheduler status、trigger list、WAITING/READY partition、WAITING retry、catch-up approval。
- **验收关注点**：HTTP 侧 p95/p99 只是入口健康；真正判断调度瓶颈要并行看 `sample-scheduler-backlog.sh` 输出的 backlog CSV。
- **核心判定**：
  - `jp_waiting` 或 `jt_ready` 在稳态窗口持续正斜率：派发/执行追不上写入。
  - `oldest_waiting_partition_seconds` 持续增大：waiting dispatch 有滞后。
  - `worker_load / worker_capacity` 接近 1 且 backlog 增长：worker 容量瓶颈。
  - `worker_load / worker_capacity` 低但 backlog 增长：优先排查调度器、资源队列、quota、outbox/Kafka。

### 2.4 `WorkerTaskLifecycleSimulation`

- **目的**：直接压 orchestrator 内部任务生命周期接口：`POST /internal/tasks/{taskId}/claim` → 可选 pause → `POST /internal/tasks/{taskId}/report`。
- **验收关注点**：claim/report 延迟、冲突率、错误率；配合 DB sampler 看 `jt_ready` 下降和 `jt_success` 上升速度。
- **前置**：必须提供 `task.lifecycle.csv`，header 为 `taskId,tenantId,workerId`；这些 task 必须处于 `READY`，并且不被真实 Worker 同时消费。
- **风险边界**：该场景会真实推进任务终态，不适合对共享开发库随手运行。

---

## 3. 仍未通过 Gatling 默认场景覆盖的部分（需在文档/运维层补齐）

| 内容 | 建议 |
|------|------|
| **Worker 真实业务代码 CPU/IO 极限** | `WorkerTaskLifecycleSimulation` 只压 orchestrator 生命周期接口；真实 worker 插件的解析、导入、导出、分发吞吐仍需端到端场景 + Prometheus / 业务结果表统计。 |
| **Kafka 滞后、DB 连接池等基础设施** | 压测期间并行查看 Prometheus/日志；不在 Gatling 断言中重复实现。 |
| **每秒业务完成数（绝对吞吐）** | 在固定 Worker 副本数与固定 launch TPS 下，用 **完成实例计数 / 时间窗口** 在报告中单独计算；或由 BI/指标平台承接。 |

---

## 4. 运行入口

所有场景均在 **`load-tests/`** 目录下执行，参数见该目录 [`README.md`](../../load-tests/README.md) 与 [`GatlingConfig`](../../load-tests/src/test/java/io/github/pinpols/batch/loadtest/GatlingConfig.java) 系统属性说明。

---

## 5. 与既有文档的关系

| 文档 | 关系 |
|------|------|
| [`load-tests/README.md`](../../load-tests/README.md) | 命令行与 profile |
| [`load-test-report.md`](./load-test-report.md) | 历史单次实验数据示例 |
