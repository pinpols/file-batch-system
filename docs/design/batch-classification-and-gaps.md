# 批量类型分类法与系统缺口分析

> 评估日期：2026-04-27。
>
> 本文目的：把"批量"这件事拆成业务/执行/触发三个正交维度，把系统现状映射到这套模型上，识别**模型层缺口（已经在用但没有一等公民化）**、**能力层缺口（业务上需要但还没做）**、**暗债（多个并行枚举 / 写死阶段 / 配置注入分裂）**，并给出"做 / 不做 / 待评估"三档建议。
>
> 与 [`capability-assessment.md`](./capability-assessment.md) 的分工：能力评估是"现在能做什么"；本文是"模型上还应该长什么"。
>
> 与 [`api-gap-analysis.md`](./api-gap-analysis.md) 的分工：API 缺口分析是接口层；本文是领域模型层。

## 1. 三维分类法

工业上"批量"没有统一定义。落到本平台，最有用的拆法是 3 个正交维度。

### 1.1 BatchType（业务类型）—— 数据从哪来 / 到哪去

| 类型 | 数据流 | 典型 | 失败模式 |
|---|---|---|---|
| **IMPORT** | 外部 → 系统（文件 / MQ / 拉取 API） | CSV → DB；Kafka → MySQL | 格式 / 字段 / 重复 |
| **EXPORT** | 系统 → 外部（文件 / 推送） | DB → Excel；DB → 下游 API | 查询压力 / 一致性 |
| **PROCESS** | 系统内部加工（聚合 / 清洗 / 状态推进） | 日报、月报；订单状态流转 | 幂等是关键 |
| **DISPATCH** | 结果向外分发（MQ / FTP / 第三方接口） | 推送 MQ；OSS 文件下发 | 外部不稳定 |
| **SYNC** | 系统之间对齐（DB→DB / CDC） | binlog；Debezium；首存 + 增量 | 一致性边界 |

### 1.2 ExecutionMode（执行模式）—— 怎么处理数据范围

| 模式 | 含义 | 关键字段 |
|---|---|---|
| **FULL** | 每次全量 | `pageNo` 全扫即可 |
| **INCREMENTAL** | 仅处理新增 / 变更 | watermark 字段（`update_time` / 主键游标 / binlog 偏移） |
| **CDC / Streaming**（可选） | 流式实时跟踪 | binlog / Debezium / Flink CDC |

注：分片（按 ID range / hash / 时间分区）是**正交**于 ExecutionMode 的能力，不是同一档枚举值。

### 1.3 TriggerType（触发方式）—— 谁让这次跑起来

| 触发器 | 来源 | 配套能力 |
|---|---|---|
| **SCHEDULED** | cron / 固定频率 | misfire / catch-up |
| **EVENT** | 文件到达 / 上游事件 / 领域事件 | 幂等去重 |
| **MANUAL / API** | 人触发 / 外部接口触发 | 审批 / 限流 |
| **DEPENDENCY** | 上游 workflow 完成 → 下游启动 | DAG join 等待 |
| **CATCH_UP / RERUN** | 补数 / 重跑 | 节流 / 漂移检测 |

### 1.4 三个维度交叉示例

| BatchType | ExecutionMode | TriggerType | 业务实例 |
|---|---|---|---|
| IMPORT | FULL | SCHEDULED | 每日凌晨从远端 SFTP 拉客户全量名单入库 |
| IMPORT | INCREMENTAL | EVENT | Kafka 流入交易明细，按 offset 增量入库 |
| EXPORT | INCREMENTAL | SCHEDULED | 每日 T+1 增量导出昨日新增风险告警 |
| PROCESS | FULL | DEPENDENCY | 等导入完成后跑完整日报聚合 |
| DISPATCH | — | EVENT | 文件入库成功后立即下发 MQ 通知下游 |
| SYNC | INCREMENTAL（CDC） | EVENT（binlog）| 把订单库的状态实时同步到分析库 |

---

## 2. 现状对照矩阵

按上述 3 个维度过本平台代码（`batch-common/src/main/java/com/example/batch/common/enums/`）。

### 2.1 BatchType ↔ `JobType` / `PipelineType`

| 你想要的 | 系统现状 | 实现位置 | 评价 |
|---|---|---|---|
| IMPORT | ✅ `JobType.IMPORT` + `PipelineType.IMPORT` + `batch-worker-import`（6 阶段：RECEIVE→PARSE→PREPROCESS→VALIDATE→LOAD→COMPLETE） | `batch-worker-import/.../DefaultImportStageExecutor` | 落地完整 |
| EXPORT | ✅ `JobType.EXPORT` + `PipelineType.EXPORT` + `batch-worker-export`（5 阶段：PREPARE→GENERATE→STORE→REGISTER→COMPLETE） | `batch-worker-export` | 落地完整 |
| DISPATCH | ✅ `JobType.DISPATCH` + `PipelineType.DISPATCH` + `batch-worker-dispatch`（5 阶段 + 渠道熔断） | `batch-worker-dispatch` | 落地完整，且额外做了 channel circuit breaker / receipt polling |
| **PROCESS** | ⚠️ 只有 `JobType.GENERAL` 兜底，没有专门的 process pipeline 模板 | `batch-orchestrator` 通过 `executionHandler` 字段把任务路由给某个 worker，但没有共享的 PROCESS stage 抽象 | 缺一等公民 |
| **SYNC** | ❌ 没有 SYNC 业务类型，没有 watermark / CDC 通道 | — | 完全缺位 |

> `JobType` 与 `PipelineType` 已经事实上重复（前者在 console-api 用，后者在 worker-core 用）。要加 PROCESS / SYNC 得改两处。属于暗债（详见 §3.2）。

### 2.2 ExecutionMode ↔ `ShardStrategy` / `RunMode`

系统**没有 ExecutionMode 这一层**。现有的两个相近枚举各自表达了一部分：

| 系统枚举 | 取值 | 实际表达的语义 |
|---|---|---|
| `ShardStrategy` | `NONE / STATIC / DYNAMIC / AUTO` | 分多少片（partition count） |
| `RunMode` | `NORMAL / RETRY / RERUN / RECOVER / COMPENSATE` | **为啥跑这一次**（接近触发原因） |

混淆点：

- **`RunMode` 与 `TriggerType` 部分重叠**：`RunMode.RERUN` 与 `TriggerType.RERUN`（V62 新增）含义相同。
- **没有"全量 / 增量"这层抽象**。增量目前靠业务在 SQL 模板里手写 `where update_time > :last_high_water_mark`，框架层完全无知 —— 没办法统一管理水位、补数窗口、乱序兜底。
- 分片（ShardStrategy）和"按数据范围切分一次执行的内容"是**两件事**，但当前模型里两个一起决定行为。

### 2.3 TriggerType ↔ `TriggerType`

| 你想要的 | 系统现状 | 实现位置 |
|---|---|---|
| SCHEDULED | ✅ `TriggerType.SCHEDULED` + Quartz / HashedWheelTimer 双引擎 | `batch-trigger/.../infrastructure/QuartzLaunchJob.java` + wheel 实现 |
| EVENT | ✅ `TriggerType.EVENT` | `TriggerController` |
| MANUAL / API | ✅ `TriggerType.MANUAL` / `TriggerType.API` | `TriggerController` |
| **DEPENDENCY**（跨 workflow） | ⚠️ workflow 内部节点 dependency 通过 `workflow_edge` 表达；**跨 workflow 的依赖触发**没有专门的 trigger 通道 | — |
| CATCH_UP | ✅ + 漂移检测（drift detection） + 节流（`CatchUpThrottle`） + 审批（MANUAL_APPROVAL / AUTO / NONE 三策略） | `batch-trigger/wheel/CatchUpThrottle.java` + `QuartzLaunchJob`:72-85 |
| RERUN | ✅（V62 加） | DDL: `db/migration/V62__rerun_semantics_and_batch_day_cas.sql` |

> 现有 6 类（API / MANUAL / EVENT / CATCH_UP / SCHEDULED / RERUN）比 PD 提的 3 类更细，**该维度已经超过最初模型**。唯一缺口是**跨 workflow 的 DEPENDENCY 触发**。

### 2.4 维度交叉支持矩阵（当前能跑通的组合）

| BatchType ↓ \ ExecMode → | FULL | INCREMENTAL | CDC |
|---|:---:|:---:|:---:|
| IMPORT | ✅ | ⚠️（业务自管 watermark） | ❌ |
| EXPORT | ✅ | ⚠️（同上） | ❌ |
| PROCESS | ⚠️（GENERAL） | ❌ | ❌ |
| DISPATCH | ✅ | — | — |
| SYNC | ❌ | ❌ | ❌ |

---

## 3. 暗债（比"功能缺口"更值得先处理的事）

### 3.1 五副 Status 副本，没有公共契约

| 枚举 | 视角 | 文件 |
|---|---|---|
| `JobStatus` | 定义层 | `batch-common/.../enums/JobStatus.java` |
| `JobInstanceStatus` | 实例运行 | `JobInstanceStatus.java` |
| `PartitionStatus` | 分区运行 | `PartitionStatus.java` |
| `TaskStatus` | worker 视角 | `TaskStatus.java` |
| `StepInstanceStatus` | 阶段运行 | `StepInstanceStatus.java` |

5 套并行演化，状态名 / 语义有漂移风险。写跨层逻辑（"实例失败 = 所有分区失败"、"任一 step 失败 → instance 失败"）时要做 5 套映射。

**建议**：抽 `BatchLifecycleStatus` 作为公共状态码（CREATED / WAITING / READY / RUNNING / SUCCESS / FAILED / CANCELLED / TERMINATED），每个具体枚举做"投影"：声明每个子状态映射到哪个公共码。不破坏现有 DB 状态值，只新增方法。

### 3.2 `JobType` 与 `PipelineType` 重复定义

`JobType { GENERAL, IMPORT, EXPORT, DISPATCH, WORKFLOW }` 在 console-api 用；`PipelineType { IMPORT, EXPORT, DISPATCH }` 在 worker-core 用。两份定义并存，要加 PROCESS / SYNC 得改两处。

**建议**：合并成统一的 `BatchType`（IMPORT / EXPORT / PROCESS / DISPATCH / SYNC，外加 GENERAL / WORKFLOW 等内部 carryover），`PipelineType` 改为 `BatchType` 的别名或子集投影。

### 3.3 Worker pipeline 阶段写死

`AbstractStageExecutor` 是模板方法，但每种 worker 的 stage **数量和名字都是 enum 写死**：
- IMPORT：6 阶段固定
- EXPORT：5 阶段固定
- DISPATCH：5 阶段固定

加新 stage（比如导入加 `ENRICH`）要改 enum + 改 executor。`pipeline_step_definition` 表只能改 step 内的实现，不能加 / 减 / 重排 stage。

> 这是个"知道但不一定要现在改"的限制。落 PROCESS / SYNC 时如果按现有套路再写死，会让模型层更乱；推荐先把 stage 完全数据化，再来加新 BatchType。但工作量较大，本期不强推。

### 3.4 配置注入路径分裂

Worker 拿到 effective config 走两个通道：
- 一部分嵌在 Kafka `TaskDispatchMessage` 里
- 一部分通过 `TaskExecutionClient.claim()` 拉

不统一的代价：管理员改了 `JobDefinition.retryMaxCount`，下一次任务派发时消息里的旧值仍生效（消息已入队），直到队列清空才会切到新值。

**建议**：claim() 时**强制**返回完整 effective config（含上下游约束、限流、租户配额、retry policy、resource quota）；消息只放 task key（`taskId / tenantId / jobCode / partitionId`），不放业务参数。

### 3.5 跨 workflow 依赖只在节点级表达

`workflow_edge` 表达节点级 SUCCESS / FAILURE / CONDITION / ALWAYS 依赖；**跨 workflow** 的 "上游 workflow 完成 → 下游 workflow 启动" 没有 trigger 通道。
当前实现该模式只能：上游 workflow 末节点显式调 trigger API → 下游 workflow 启动。耦合到上游业务代码里。

**建议**（仅业务有诉求时做）：补 `TriggerType.DEPENDENCY` + `dependency_definition` 表（上游 `workflowCode` + 下游 `workflowCode` + 等待策略：first-success / all-success / time-window）。

---

## 4. 缺口优先级与建议落地顺序

| 优先级 | 事项 | 价值 | 影响面 | 估工 |
|---|---|---|---|---|
| **P0** ✅ | `ExecutionMode` 一等公民化（FULL / INCREMENTAL / CDC） + `job_definition.execution_mode` + `job_instance.high_water_mark_in/out` 字段 + 运行时 IN/OUT 双向打通 | 增量 / 全量分明，统一限流 / 补数视角 | DB 迁移 + console UI 加字段 + worker SDK 加 watermark 接口 | M |
| **P0** ✅ | `BatchLifecycleStatus` 公共投影（5 个 Status 加 `lifecycle()` 方法） | 跨层逻辑统一防漂移 | 纯加方法，不破坏 DB | S |
| **P1** | `JobType` ↔ `PipelineType` 合并为 `BatchType`（含 PROCESS） | 加 PROCESS / SYNC 改一处 | 一次 rename + 投影 | S |
| **P1** | claim() 强制返回 effective config | 配置一致性 | orchestrator + worker 接口契约改动 | M |
| **P2** | `batch-worker-process` 模块（或 worker-core 加 `ProcessStageStep`） | 把"加工类"业务从 GENERAL / 业务自写脚本里收敛 | 看业务是否真有典型场景 | M |
| **P2** | `TriggerType.DEPENDENCY`（跨 workflow 依赖） | 业务侧解耦上下游 trigger 调用 | 一次 trigger 通道 + DSL | M |
| **P3** | SYNC（CDC / binlog 通道） | 中长期 | 新模块 + Debezium / Flink CDC 选型 | L |
| **P3** | Pipeline stage 数据化（自定义 stage 序列） | 灵活 | 大 | L |

> 估工档位：S = 1 PR；M = 2-3 PR；L = 一个迭代。

### 4.1 P0 第一步：`ExecutionMode` 落地草案 ✅ 已落地

> **2026-04-27 状态**：commit `7584359f`(模型层 + V73 migration)+ `0d64f69c`(运行时 IN/OUT 双向打通)。完整运行时回路与时序见 [`../architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) §7.8。

新增枚举：

```java
// batch-common/src/main/java/com/example/batch/common/enums/ExecutionMode.java
public enum ExecutionMode {
  FULL,            // 每次全跑;典型:小表全量重算、初始化
  INCREMENTAL,     // 按 watermark 增量;典型:T+1 增量导出
  CDC              // 流式跟踪;首期占位,实际不实现
}
```

DB 迁移（V63 起）：

```sql
ALTER TABLE job_definition
  ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'FULL',
  ADD COLUMN watermark_field VARCHAR(64);

ALTER TABLE job_instance
  ADD COLUMN high_water_mark_in  VARCHAR(64),
  ADD COLUMN high_water_mark_out VARCHAR(64);
```

console-api 与 worker SDK：
- `ConsoleJobDefinitionRequest` 加两个字段
- `TaskDispatchMessage` 加 `highWaterMarkIn`
- worker 完成时回报 `highWaterMarkOut`，orchestrator 写回 `job_instance` 作为下次启动的 input

不做 storage migration（旧 job 默认 FULL，行为不变）。

### 4.2 P0 第二步：`BatchLifecycleStatus` ✅ 已落地

> **2026-04-27 状态**：commit `6ee278ab`。5 个具体 Status 投影 `lifecycle()` 方法均通过单测覆盖；`ConsoleMetaEnumRegistrationTest.EXCLUDED` 加白(派生公共投影,不对外暴露)。

```java
// batch-common/src/main/java/com/example/batch/common/enums/BatchLifecycleStatus.java
public enum BatchLifecycleStatus {
  CREATED, WAITING, READY, RUNNING,
  SUCCESS, FAILED, CANCELLED, TERMINATED
}

// 各具体 Status 加投影方法
public enum JobInstanceStatus {
  // ... existing values
  public BatchLifecycleStatus lifecycle() { /* mapping */ }
}
```

不改 DB，只新增方法 + 单测投影一致性（5 个枚举的所有值必须能映射到一个 lifecycle）。

---

## 5. 不打算做的事（明确边界）

| 项 | 理由 |
|---|---|
| 把 GENERAL 改名为 PROCESS | GENERAL 的语义是"未归类 / 无 pipeline 模板"，和 PROCESS 不等同。PROCESS 应**新增**而非 rename。 |
| 流批一体（Kafka + Flink） | 平台定位是批，硬塞 streaming 引入运维复杂度，收益不清晰。 |
| 把所有 stage 完全数据化 | 模型上更优雅但工作量大，先观察 PROCESS 落地需求再评估。 |
| 拆 console-api 的 `JobType` 之外另起一份 enum | 重复就该合并；不要再开第三份。 |
| 给 RunMode 加新值（如 `INCREMENTAL`） | RunMode 应**收缩**到"为啥跑这一次"语义；增量是 ExecutionMode 的事。 |

---

## 6. 与其它文档的关系

| 主题 | 本文 | 现有文档 |
|---|---|---|
| 完整能力评估 | — | [`capability-assessment.md`](./capability-assessment.md) |
| 接口缺口 | — | [`api-gap-analysis.md`](./api-gap-analysis.md) |
| 数据模型 DDL | §4.1 给出 V63 草案 | [`data-model-ddl.md`](./data-model-ddl.md) |
| 触发模型 | §1.3 / §2.3 | [`../architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) §1 |
| 文件链路 | IMPORT/EXPORT/DISPATCH 工程实现 | [`file-pipeline-design.md`](./file-pipeline-design.md) |
| 多租户与安全 | watermark 字段需考虑 PII | [`multi-tenant-and-security.md`](./multi-tenant-and-security.md) |

## 7. 一句话总结

系统在"运维原语 / 调度 / DAG / 容错"维度已经超过工业批处理框架平均水平。**真正的缺口在模型层的两个正交维度没拆干净**：

- `BatchType` 缺 PROCESS / SYNC
- `ExecutionMode` 没和 `ShardStrategy` / `RunMode` 分开

先做 §4.1 / §4.2 两件 P0（不破坏现有运行时，只新增模型字段和投影），再讨论要不要补 PROCESS / SYNC 的实际 worker 模块。
