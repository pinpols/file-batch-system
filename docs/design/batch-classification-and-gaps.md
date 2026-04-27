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
| **CDC / Streaming**（不做，见 §5） | 流式实时跟踪 | binlog / Debezium / Flink CDC（枚举值保留占位） |

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
| **PROCESS** | ✅ `JobType.PROCESS` + `PipelineType.PROCESS` + `batch-worker-process`（5 阶段：PREPARE→COMPUTE→VALIDATE→COMMIT→FEEDBACK） | `DefaultProcessStageExecutor` + `sqlTransformCompute` + `ProcessComputePlugin` | 已补一等公民；常见 SQL 加工可纯配置驱动，复杂业务通过插件扩展 |
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

| BatchType ↓ \ ExecMode → | FULL | INCREMENTAL | CDC[^cdc-not-impl] |
|---|:---:|:---:|:---:|
| IMPORT | ✅ | ✅[^framework-watermark] | — |
| EXPORT | ✅ | ✅[^framework-watermark] | — |
| PROCESS | ✅ | ✅（`sqlTransformCompute` 可配置读取 IN、写出 OUT） | — |
| DISPATCH | ✅ | — | — |
| SYNC[^sync-not-impl] | — | — | — |

[^framework-watermark]: 框架层已透传 `PulledTask.highWaterMarkIn` 并由成功路径回写 `TaskExecutionReport.highWaterMarkOut`（P0-1.5 + P1-2.x 落地，详见 [`../architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) §7.8）。业务 stage 自取并消费水位即可，平台不再要求自管 watermark 表。
[^cdc-not-impl]: CDC / 流式不做（详见 §5）；`ExecutionMode.CDC` 枚举值仅作占位，避免后续破坏性变更。
[^sync-not-impl]: SYNC 不做（详见 §5）；`BatchType.SYNC` 枚举值仅作占位。

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

> **2026-04-28 决策：不做**（详见 §5）。三点理由：
> 1. PROCESS 已经事实上半数据化 —— `DefaultProcessStageExecutor` 支持内联 + DB `pipeline_step_definition` 驱动 + `ProcessComputePlugin` 分派，"业务自定义阶段"诉求已通过 plugin 收敛。
> 2. IMPORT / EXPORT / DISPATCH 的 stage 序列工业上稳定，加新 stage（如 IMPORT 加 ENRICH）的频率远低于全数据化的重写 + 测试矩阵代价。
> 3. 平台定位是 SaaS 不是 PaaS：给业务的是稳定 stage 模板 + 可插拔实现，不是任由业务编排 stage 序列。

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
| **P1** ✅ | `JobType` ↔ `PipelineType` 合并为 `BatchType`（含 PROCESS） | 加 PROCESS / SYNC 改一处 | 一次 rename + 投影 | S |
| **P1** ✅ | claim() 强制返回 effective config | 配置一致性 | orchestrator + worker 接口契约改动 | M |
| **P2** ✅ | `batch-worker-process` 模块（或 worker-core 加 `ProcessStageStep`） | 把"加工类"业务从 GENERAL / 业务自写脚本里收敛 | 独立 worker 模块 + 默认 PROCESS stage + 配置驱动 `sqlTransformCompute` + `ProcessComputePlugin` 扩展点 | M |
| ❌ 不做 | `TriggerType.DEPENDENCY`（跨 workflow 依赖） | — | 详见 §5（评估完成 2026-04-27） | — |
| ❌ 不做 | SYNC（`BatchType.SYNC` worker） / `ExecutionMode.CDC` 实现 | — | 详见 §5（评估完成 2026-04-28） | — |
| ❌ 不做 | Pipeline stage 数据化（自定义 stage 序列） | — | 详见 §5（评估完成 2026-04-28） | — |

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

### 4.3 P1 第一步：`BatchType` 公共投影 ✅ 已落地

> **2026-04-27 状态**：新增 `batch-common/.../enums/BatchType.java` 含 7 个枚举值（IMPORT / EXPORT / PROCESS / DISPATCH / SYNC / GENERAL / WORKFLOW）。PROCESS 已补 `batch-worker-process` 最小模块，SYNC 仅保留占位枚举（不实现 worker，详见 §5）。`JobType` / `PipelineType` 各加 `batchType()` 投影方法，单测覆盖投影完整性 + 共享业务类型一致性（IMPORT / EXPORT / PROCESS / DISPATCH 两边映射相等）。

```java
// batch-common/src/main/java/com/example/batch/common/enums/BatchType.java
public enum BatchType implements DictEnum {
  IMPORT, EXPORT, PROCESS, DISPATCH, SYNC, GENERAL, WORKFLOW
}

// JobType / PipelineType 加投影
public BatchType batchType() { /* 1:1 映射 */ }
```

PROCESS 落地后通过 V74 扩展 `job_definition.job_type`、`pipeline_definition.pipeline_type`、`pipeline_instance.pipeline_type` 和 `pipeline_step_definition.stage_code` 的 CHECK 约束；业务"按业务类型派发"的逻辑仍可依赖 `batchType()` 投影。

`ConsoleMetaQueryService.REGISTRATIONS` 加 `batchType` + `docs/api/console-api.openapi.yaml` 的 `CommonResponseMetaEnums` 同步追加 `batchType` / `executionMode`（后者补 P0-1 漏登记）。

### 4.4 P1 第二步：claim() 返回 effective config ✅ 已落地（含 Stage 2）

> **2026-04-27 状态**：Stage 1 + Stage 2 全部完成。
>
> - **Stage 1**：orchestrator 在 `/internal/tasks/{taskId}/claim` 认领成功时回 `EffectiveTaskConfig` body，worker 优先用其字段。
> - **Stage 2**：`TaskDispatchMessage` schemaVersion v1→v2 瘦身完成 —— 删 `taskType` / `taskSeq` / `businessKey` / `payload` / `highWaterMarkIn`（业务字段全部走 claim），只保留 task key（tenantId / taskId / jobInstanceId / jobPartitionId / instanceNo / jobCode / traceId / idempotencyKey / dispatchAt）+ 路由元数据（workerType / selectedWorkerId / priorityBand —— publisher 路由 / consumer accepts 必需，不能从 DB 重读）。
> - retry / reclaim 的 `RunMode` override 改由 `JobTaskMapper.updatePayload` 持久化到 `job_task.task_payload`，worker CLAIM 时 `EffectiveTaskConfig` 实时读到。

**Stage 1 改动**：

```java
// batch-common/src/main/java/com/example/batch/common/dto/EffectiveTaskConfig.java
public record EffectiveTaskConfig(
    String tenantId, Long taskId, Long jobInstanceId, Long jobPartitionId, String instanceNo,
    String jobCode, String taskType, Integer taskSeq, String workerType, String priorityBand,
    String businessKey, String idempotencyKey, String payload, String traceId,
    // ExecutionMode + 水位:从 job_definition + job_instance 实时读
    String executionMode, String watermarkField, String highWaterMarkIn,
    // Retry / timeout:从 job_definition 实时读,管理员改完立即生效
    String retryPolicy, Integer retryMaxCount, Integer timeoutSeconds) {}

// TaskExecutionClient.claim 签名:boolean → Optional<EffectiveTaskConfig>
//   Optional.empty()  = HTTP 4xx 失败
//   Optional.of(cfg)  = HTTP 200 成功(cfg 字段可能全 null,旧 orchestrator 兼容)
```

兼容性：
- 旧 worker（P1-2.1 之前）忽略 response body，继续走 `TaskDispatchMessage` 字段，无感
- 旧 orchestrator 返 bodyless 200，新 worker 收到 `EMPTY_EFFECTIVE_CONFIG` sentinel（全 null），fallback 到 message
- `TaskDispatchExecutor.preferConfig(fromConfig, fromMessage)` 在 PulledTask 拼装时 per-field 优先 response

**Stage 2 改动**（合入本批）：

```java
// batch-common/src/main/java/com/example/batch/common/kafka/TaskDispatchMessage.java
// schemaVersion 从 "v1" 升 "v2";删 taskType / taskSeq / businessKey / payload /
// highWaterMarkIn(全部 worker 走 claim 拿);保留 task key + 路由元数据。
public record TaskDispatchMessage(
    String schemaVersion,    // "v2"
    String tenantId, Long jobInstanceId, Long jobPartitionId, Long taskId, String instanceNo,
    String jobCode,
    String workerType,       // 路由元数据:worker accepts() 过滤
    String selectedWorkerId, // 路由元数据:direct dispatch topic
    String priorityBand,     // 路由元数据:PRIORITY 模式 topic 后缀
    String traceId, String idempotencyKey, Instant dispatchAt) {}

// TaskDispatchExecutor 删 preferConfig 兼容层,业务字段直接读 effective:
// task.setPayload(effective.payload());      // 不再 fallback 到 message.payload
// task.setBusinessKey(effective.businessKey());
// task.setHighWaterMarkIn(effective.highWaterMarkIn());

// retry/reclaim 的 RunMode override 改持久化到 job_task.task_payload:
// JobTaskMapper.updatePayload(tenantId, taskId, mergedPayloadWithRunMode)
// worker CLAIM 时 EffectiveTaskConfig 实时读 job_task.task_payload,看到 run_mode。
```

兼容性（沿用 Stage 1 的滚动升级思路）：Stage 2 部署 orchestrator 时所有 worker 已是 P1-2.1+，以 effective config 为权威，message v2 缺字段不影响业务执行。Jackson 反序列化未知字段被忽略，旧 v1 消息（含已删字段）被新 worker 解析为 v2 时已删字段直接丢弃。

### 4.5 P2：PROCESS worker 模块 ✅ 已落地 WAP+bookends 真五段版

> **2026-04-28 状态(第二轮)**：把 PROCESS 从"5 stage 模板表面 + COMPUTE 一段干所有事"重做为真正的 **WAP+bookends**(Write-Audit-Publish + 前后置)—— 5 个 stage 各自承担明确职责,与 Netflix Atlas / Iceberg branch 等成熟数据平台的模式对齐。

WAP+bookends 五段语义:

| stage | 职责 | sqlTransformCompute 内置实现 |
|---|---|---|
| **PREPARE** | Pre-flight:解析 spec / 校验 SQL AST / identifier 校验 / 目标表存在性 / schema allowlist / 命名参数闭包;任何错误早停 | parse + jsqlparser + `information_schema.tables` |
| **COMPUTE** | Write:执行源 SELECT,每行 `jsonb_build_object` 序列化写入 `batch.process_staging`(不直接写 target) | `INSERT INTO batch.process_staging ... SELECT :batchKey, ..., jsonb_build_object(...) FROM (<sourceSql>) base` |
| **VALIDATE** | Audit:在 staging 上跑数据质量规则;可配置 `validations` SQL 列表(每条返回 `pass BOOLEAN, message TEXT`);失败阻断 publish | 用户配置的 checkSql,通过 `:batchKey` 命名参数过滤本批 |
| **COMMIT** | Publish:用 `jsonb_populate_record(NULL::biz.target, payload)` 把 staging payload 反序列化为目标表行;单 SQL `INSERT ... ON CONFLICT` 原子上线 | `INSERT INTO biz.target SELECT (rec).* FROM (... jsonb_populate_record ...) ON CONFLICT (...) DO UPDATE` |
| **FEEDBACK** | Post-hook:清理 staging(`DELETE FROM batch.process_staging WHERE batch_key=:bk`);水位 `highWaterMarkOut` 通过 attributes 透传上报 orchestrator;异常仅 log warn 不抛(target 已写入) | `DELETE FROM batch.process_staging WHERE batch_key = :batchKey` |

关键基础设施:

- **新增 V75 migration**:`batch.process_staging`(共享 staging 表,JSONB payload 列);不需要为每个目标表手工建 staging
- **batch_key**:PREPARE 阶段一次性生成 `process-<taskId>-<traceId>`,5 个 stage 共享
- **`ProcessComputePlugin` 接口拆 5 个 lifecycle 方法**:`prepare / compute / validate / commit / feedback`,全部带默认 no-op;自定义插件按需 opt-in(只重写 `compute()` 也合法,框架仍跑全 5 stage)
- **plugin 解析在 PREPARE 前一次性完成**:`DefaultProcessStageExecutor` 扫一遍 step 找 COMPUTE step 的 `impl_code`,resolve plugin 缓存到 `ProcessJobContext.resolvedPlugin`
- **`ProcessRuntimeKeys`**:`PROCESS_COMPUTE_STEP_PARAMS / PROCESS_PARSED_SPEC / PROCESS_STAGED_COUNT / PROCESS_PUBLISHED_COUNT` 让 5 个 lifecycle 方法在不同 stage step 之间传递状态
- **VALIDATE 校验 SQL 跳过 schema allowlist**:用户的 checkSql 必须能读 `batch.process_staging`,但目标 SQL allowlist 只包含业务 schema;新加 `validateUserCheckSelect()` 走 AST + 禁 SELECT * 但不强制 schema 名单

E2E 三个用例(`ProcessPipelineE2eIT`)覆盖:
1. `wap_sqlTransform_publishesTargetAndCleansStaging`:完整 happy path —— staging 临时持有 → COMMIT 后目标表正确写入 → FEEDBACK 后 staging 清空,`pipeline_step_run` 5 条带 stagedCount/publishedCount/水位
2. `wap_sqlTransform_validationFailureAbortsCommit`:用户校验规则失败 → COMMIT 不跑 → 目标表保持空 → staging 留作 forensics
3. `wap_customPlugin_simpleComputeOnly_runsAll5StagesAsNoOpForOthers`:自定义插件只重写 `compute()`,prepare/validate/commit/feedback 走默认 no-op,pipeline 5 段跑通

仍保留边界:

- PROCESS 当前支持线性 pipeline,不做 DAG / workflow 级分支编排;复杂依赖仍交给 workflow。
- staging 用单张共享表 + JSONB payload;类型还原靠 PG `jsonb_populate_record`,对绝大多数列类型(numeric/date/timestamp/text)够用,极端类型(自定义复合类型、PostGIS)需单独验证。
- 当前 staging 没有 retention 清理 scheduler;运维对孤儿行(任务挂了没走到 FEEDBACK)需靠 `staged_at` 索引手动清,下一轮可加自动清理。
- 跨 workflow 依赖仍不随 PROCESS 一起做,继续由 §4.6 的触发模型单独评估。

> ⚠️ **2026-04-28 深度评估发现的洞**：详细列表见 [`process-worker-known-issues.md`](./process-worker-known-issues.md)。简版：**2 个 P0**（WAP COMMIT/FEEDBACK 非原子 → 孤儿 staging；staging 缺 tenant_id 强过滤 → 防御纵深缺失）、**5 个 P1**（重跑 staging 永久泄漏 / attributes 污染 SQL 命名参数 / COMPUTE 二次跑 sourceSql / writeMode=INSERT 重跑必 UK 冲突 / staging 无写入上限）、**9 个 P2**。按该文档 §5 sprint 切片推进，先修 P0+P1-6。

### 4.6 P2：`TriggerType.DEPENDENCY` 评估 ✅ 已闭环，暂缓实现

> **2026-04-27 状态**：不新增 `TriggerType.DEPENDENCY`，不新增 `dependency_definition` 表。跨 workflow 编排继续用显式 trigger API、EVENT 触发，或在同一 workflow 内用 `workflow_edge` 表达节点依赖。

暂缓原因：
- 当前平台已经有 `API / MANUAL / EVENT / CATCH_UP / SCHEDULED / RERUN` 六类触发，跨 workflow 依赖不是触发通道缺失，而是还缺少足够明确的业务 DSL。
- DEPENDENCY 一旦平台化，需要同时定义上游匹配、等待策略、窗口、失败传播、重跑语义、循环依赖检测和观测口径，复杂度接近一套跨 workflow 编排器。
- 现阶段用上游末节点显式调用 trigger API 或发业务事件，虽然耦合更高，但语义可见、排障简单，适合需求还不稳定时保守推进。

重新启动条件：
- 多个业务反复出现"上游 workflow 成功/部分成功后自动拉起下游 workflow"且手写触发逻辑开始重复。
- 依赖关系需要由运维/配置人员维护，而不是写在业务代码里。
- 业务已经确认等待策略（first-success / all-success / time-window）、失败传播策略和重跑策略。

落地建议仍是 M 级：先做一次 trigger 通道 + 最小 DSL（上游 workflowCode、下游 workflowCode、等待策略、窗口），不要一开始做完整 DAG 跨 workflow 调度器。

---

## 5. 不打算做的事（明确边界）

| 项 | 理由 | 重启条件 |
|---|---|---|
| 把 GENERAL 改名为 PROCESS | GENERAL 的语义是"未归类 / 无 pipeline 模板"，和 PROCESS 不等同。PROCESS 应**新增**而非 rename。 | — |
| 流批一体（Kafka + Flink） | 平台定位是批，硬塞 streaming 引入运维复杂度，收益不清晰。 | — |
| `ExecutionMode.CDC` 实现（流式 / binlog / Debezium / Flink CDC） | 平台暂无流式跟踪场景；引入 CDC 通道意味着另起一套运维形态（offset 管理 / 异常重放 / 顺序保证），收益不清晰。`ExecutionMode.CDC` 枚举值保留为占位（避免后续破坏性变更），但不会有对应 worker。 | 业务出现 ≥3 个真实 binlog/Debezium 同步场景，且一致性边界、重放策略已明确。 |
| `BatchType.SYNC` worker 模块 | SYNC 业务实质等同于 CDC；CDC 不做，SYNC 也不做。`BatchType.SYNC` 枚举值保留占位。 | 同 CDC。 |
| `TriggerType.DEPENDENCY`（跨 workflow 依赖） | 跨 workflow 编排复杂度接近 mini Airflow（等待策略 / 失败传播 / 循环依赖检测 / 重跑语义 / bizDate 对齐），业务 DSL 未稳定就平台化等于猜。替代方案：多 job 串起来 → 包成同一 workflow 用 `workflow_edge`；真要跨 workflow → 上游末节点 emit 业务事件 + 下游 `TriggerType.EVENT` 监听（耦合在事件协议里，不耦合在调用代码里）。 | ≥3 个跨 workflow 案例 + 等待 / 失败 / 重跑策略已收敛 + 由运维 / 配置人员维护（而非业务代码）。详见 §4.6。 |
| Pipeline stage 完全数据化（自定义 stage 序列） | (1) PROCESS 已半数据化（`pipeline_step_definition` + `ProcessComputePlugin`）覆盖"业务自定义阶段"诉求；(2) IMPORT / EXPORT / DISPATCH 的 stage 序列工业上稳定；(3) 平台是 SaaS 不是 PaaS，给业务稳定模板 + 可插拔实现即可，不应任由编排 stage。ROI 倒挂：模型优雅 vs. 大量重构 + 测试矩阵 + 兼容老数据。 | ≥3 个不同插入点的自定义 stage 诉求（不只是 IMPORT 加 ENRICH 一处），或新增 BatchType worker 需完全不同的 stage 序列。 |
| 新增 `BatchType.EXEC` / `TASK_RUNNER`（跑用户脚本 / 远程命令 / SSH / `bash xxx.sh` / K8s job 等通用执行模型） | 四项 BatchType 应满足的属性全部不达标：(1) **没有稳定业务模板**——"跑用户给的命令"不带业务语义，stage 序列无从约束；(2) **失败模式平台无法定义**——命令的失败语义取决于命令本身，平台只能数 exit code；(3) **观测口径退化**——没有 counter/latency/error rate 这种业务维度，只剩黑盒轮询；(4) **重跑等价性框架层无法保证**——完全靠用户脚本自证幂等，PROCESS 的 WAP staging 那套护栏失效。开口子的代价：脚本质量决定平台 SLA、多租户安全边界变薄（执行权限/文件系统隔离/kill 语义全是独立项目）、状态机契约破坏。**真有跑遗留脚本的诉求，三条退路（按推荐度）**：①重写成 `ProcessComputePlugin` 走 PROCESS WAP；②业务侧自跑 cron/Airflow/K8s CronJob，跑完发 EVENT，平台用 `TriggerType.EVENT` 接；③包成 HTTP service，IMPORT 用 `API` channel 拉、DISPATCH 用 `API_PUSH` 推，至少把熔断/重试/超时收敛到现有 channel 框架。 | 同时满足三条：≥10 个业务团队明确诉求 + 跨租户安全模型先行（执行环境隔离 / 权限收敛 / kill 语义） + 框架层能给出比"调 exit code"更强的失败语义（如结构化进度上报 / 可中断信号）。三个都满足才回头评估，否则一律拒。 |
| 拆 console-api 的 `JobType` 之外另起一份 enum | 重复就该合并；不要再开第三份。 | — |
| 给 RunMode 加新值（如 `INCREMENTAL`） | RunMode 应**收缩**到"为啥跑这一次"语义；增量是 ExecutionMode 的事。 | — |

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

系统在"运维原语 / 调度 / DAG / 容错"维度已经超过工业批处理框架平均水平。**模型层已收敛，下一步不是继续补枚举**：

- `BatchType` / `ExecutionMode` 已一等公民化（FULL / INCREMENTAL 端到端打通），PROCESS 已有最小 worker 模块。
- **四件事明确不做**（详见 §5，含重启条件）：`ExecutionMode.CDC` / `BatchType.SYNC` worker、`TriggerType.DEPENDENCY` 跨 workflow 依赖、Pipeline stage 完全数据化、`BatchType.EXEC` / `TASK_RUNNER` 通用执行模型（跑脚本 / 远程命令）。占位枚举值（`CDC` / `SYNC`）保留以避免后续破坏性变更。

真正的工作转向让真实 PROCESS 业务插件沉淀 `VALIDATE / COMMIT / FEEDBACK` 的边界。
