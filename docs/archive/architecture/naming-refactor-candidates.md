# 命名重构候选清单

> 这份清单不是强制重构计划，而是“最容易继续引起误解”的命名候选池。
> 优先级从高到低，建议先处理**会跨模块传播歧义**的名称，再处理局部领域名。

> 职责分工：
> - [`core-model.md`](./core-model.md) 负责定义权威边界和术语口径
> - 本文只负责列出候选项、状态、影响面、落点文件和推进顺序
> - 已在 `core-model.md` 固化的内容，本文只保留引用，不重复定义

## 1. 候选总览

| 优先级 | 当前命名 | 混淆点 | 建议方向 | 影响范围 | 状态 |
|---|---|---|---|---|---|
| P0 | `PipelineContext` | orchestrator 和 worker-core 都在用，含义不同 | 统一为 `ExecutionContext`，旧名仅作历史检索词 | 高 | 已完成 |
| P0 | `jobCode` / `pipelineCode` / `flowCode` | 同一业务对象在不同模块被叫成三种名字 | 统一一个主名，其他保留兼容口径 | 高 | 已完成 |
| P0 | `retry_count` / `attemptNo` / `publishAttempt` / `run_seq` | “次数”散落多处，语义相近但不等价 | 区分“业务重试次数”和“执行尝试序号”，统一命名约定 | 高 | 已完成 |
| P1 | `TaskStatus` / `StepInstanceStatus` / `WorkflowRunStatus` | 都是状态，但层级不同，容易被混成同一层 | 保留枚举名，文档上强制区分对象层级 | 中 | 已固化 |
| P1 | `Step` / `StepResult` / `StageExecutionResult` | `step` 和 `stage` 交替出现，阅读成本高 | 边界已在 [core-model.md](./core-model.md) 3.6 固化，本文不再重复定义 | 中 | 已固化 |
| P1 | `workerId` / `workerCode` / `workerGroup` | 认证标识、业务编码、消费分组混在一起 | 统一命名含义，必要时拆分 DTO 字段 | 中 | 已完成 |
| P2 | `CompensationSubmitCommand` / `ApprovalCommand` | 命令对象边界相近，业务上容易串 | 边界已在 [core-model.md](./core-model.md) 6.2 / 6.4 固化，本文只保留落点说明 | 中 | 已固化 |
| P2 | `run_mode` | 过去没有统一上下文意图字段，容易被误当状态 | 已统一为运行时 `run_mode`；如需筛选或报表再单独评估落库 | 中 | 已完成 |

---

## 2. 候选说明与归档

### 2.1 `ExecutionContext`

现状：
- orchestrator 侧：[`ExecutionContext.java`](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/com/example/batch/orchestrator/domain/pipeline/ExecutionContext.java)
- worker-core 侧：[`ExecutionContext.java`](/Users/dengchao/Downloads/file-batch-system/batch-worker-core/src/main/java/com/example/batch/worker/core/support/ExecutionContext.java)
- 旧名 `PipelineContext` 已转为历史检索词，不再保留独立类型

问题：
- 名字一样，但职责不一样
- 一个更像“流程编排上下文”，一个更像“worker 阶段上下文”

做法： 统一为 `ExecutionContext`

### 2.2 `jobCode` / `pipelineCode` / `flowCode`

现状：
- orchestrator 代码里更常见 `jobCode`
- 流程执行代码里更常见 `pipelineCode`
- 设计文档里还出现 `flowCode`

问题：
- 同一个业务边界被多个名词表达
- 新人很难判断它们是不是同一个东西

建议：
- 先选一个主名
- 其他名字保留兼容字段，但不要继续扩散

### 2.3 `retry_count` / `attemptNo` / `publishAttempt` / `run_seq`

现状：
- DB 中常见 `retry_count`
- MQ / 消息层常见 `attemptNo`
- outbox 层出现 `publishAttempt`
- 工作流节点层出现 `run_seq`

问题：
- 大家都在说“第几次”，但不是同一个层级的“第几次”
- 容易把“重试次数”和“执行序号”混为一谈

建议：
- DB 层优先保留 `retry_count`
- 消息层优先保留 `attemptNo`
- 文档里明确每个字段的层级，不再口头混用

### 2.4 `Step` / `Stage` 混用

这组边界已经在 [core-model.md](./core-model.md) 3.6 固化。本文只保留结论：

- `Step` 是编排和审计视角的领域步骤
- `Stage` 是 worker 内部执行阶段
- `job_step_instance` 是 `Step` 级镜像，不是 `Stage` 的持久化替身

### 2.5 `workerId` / `workerCode` / `workerGroup`

现状：
- `workerId`：运行时实例标识
- `workerCode`：业务或配置侧编码
- `workerGroup`：消费与调度分组

问题：
- 这三个字段经常同时出现，但职责不同
- 如果没有边界说明，很容易把“实例标识”当“业务编码”

建议：
- 保持三个字段，但补齐定义
- 需要对外暴露时优先输出语义最稳定的字段

---

## 3. 适合后续重构的低风险项

| 项 | 说明 | 建议 |
|---|---|---|
| `run_mode` 落库评估 | 运行时键已统一，但还没有查询型持久化字段 | 本轮继续 deferred；只在确实需要筛选 / 报表 / 审计检索时再评估落库 |
| `CompensationSubmitCommand` / `ApprovalCommand` | 命令边界清晰，但命名可再显式一点 | 先不动代码，只补文档和 API 说明 |
| `Step` / `Stage` | 已在 [core-model.md](./core-model.md) 3.6 固化 | 本文不再重复定义，后续只跟踪是否仍有旧称谓残留 |

---

## 4. 处理顺序复盘

1. 先收口跨模块传播的语义歧义：`ExecutionContext`、`jobCode` 主名、`retry_count / attemptNo / publishAttempt / run_seq`
2. 再收紧容易被业务人员误解的字段：`workerId / workerCode / workerGroup`
3. 最后固化局部领域术语：`TaskStatus` / `StepInstanceStatus` / `WorkflowRunStatus`、`Step / Stage`、`CompensationSubmitCommand / ApprovalCommand`

---

## 5. P0 / P1 / P2 重构排期表

### 5.1 完成情况

- 已落地：`PipelineContext` -> `ExecutionContext`
- 已落地：`jobCode` / `pipelineCode` / `flowCode` 的主名收敛，代码层已统一到 `jobCode`
- 已落地：`retry_count` / `attemptNo` / `publishAttempt` / `run_seq` 的语义分层
- 已落地：`run_mode` 已按运行时上下文意图收口，不改表
- 已落地：`workerId` / `workerCode` / `workerGroup` 的代码边界收紧
- 已固化：`TaskStatus` / `StepInstanceStatus` / `WorkflowRunStatus` 的层级边界，已写入 [core-model.md](./core-model.md)
- 已固化：`Step` / `Stage` 的边界，已写入 [core-model.md](./core-model.md)
- 已固化：`CompensationSubmitCommand` / `ApprovalCommand` 的命令边界，已写入 [core-model.md](./core-model.md)

### 5.2 当前进度

- P0 的**口径统一与边界固化**已完成：
  - [`core-model.md`](/Users/dengchao/Downloads/file-batch-system/docs/architecture/core-model.md)
  - 设计说明书关键章节引用已补齐
  - 关键 `ExecutionContext` / `PipelineRuntimeKeys` 类注释已明确边界
- 数据库层面的结论已经收口：`run_mode` 落库查询继续 deferred，物理列统一重命名明确冻结，不进入本轮

| 优先级 | 目标 | 具体动作 | 推荐产出 | 依赖 | 风险 |
|---|---|---|---|---|---|
| P0 | 消除跨模块语义歧义 | 统一 `ExecutionContext` 边界，清理 `PipelineContext` 历史口径，整理 `jobCode / pipelineCode / flowCode` 口径，补齐 `retry_count / attemptNo / publishAttempt / run_seq` 的层级定义 | 核心模型文档回写 + 代码命名决策记录 | `core-model.md` | 高：涉及多模块阅读和联调认知 |
| P0 | 锁定恢复语义 | 统一 `retry / rerun / recover / compensate` 的调用边界和 API 说明，确保控制台、运维和设计文档口径一致 | 恢复术语对照表 + API 说明补丁 | `core-model.md`、`design-gap-audit.md` | 高：容易误改成同义词 |
| P1 | 统一局部领域术语 | 明确 `workerId / workerCode / workerGroup` 的稳定定义；`Step` / `Stage` 已在 core-model 固化 | 命名约定附录 + 代码注释回写 | P0 完成 | 中：影响范围较小，但容易产生新的歧义 |
| P1 | 收敛命令对象边界 | 将 `CompensationSubmitCommand` / `ApprovalCommand` 的职责边界写清楚，避免表单、命令、审计混用 | 命令对象边界说明 | P0 完成 | 中：主要是领域建模一致性 |
| P2 | 文档补位 | 把仍然混用旧称谓的设计说明、实施状态和联调用语逐段回写到 `core-model.md` 和本清单 | 文档引用收口 | P0 / P1 完成 | 低：不改代码，只改文档 |
| P2 | 评估数据库落点 | 对 `run_mode` 是否需要真正落库、以及物理列重命名是否值得做 PoC 评估 | 落库 / 重命名 PoC 清单 | P0 / P1 完成 | 中：可能引起较多连锁修改 |

### 执行节奏复盘

1. 先完成 P0，保证跨模块沟通不再继续分叉。
2. 再完成 P1，把局部术语和命令边界收干净。
3. 当前剩余工作主要是 P2 文档补位，以及 `run_mode` 是否需要落库、物理列重命名的评估。

---

## 6. 参考文档

- [core-model.md](/Users/dengchao/Downloads/file-batch-system/docs/architecture/core-model.md)
- [architecture-truth.md](/Users/dengchao/Downloads/file-batch-system/docs/architecture/architecture-truth.md)
- [design-gap-audit.md](/Users/dengchao/Downloads/file-batch-system/docs/architecture/design-gap-audit.md)

---

## 7. 数据库影响分级

> 这部分把前面的候选项按“是否需要数据库层面改动”再切一刀。默认原则是：**能不改表就不改表**，先通过命名口径、DTO、Mapper、日志字段和文档收口。

### 7.1 三列表总览

| 类别 | 候选项 | 结论 |
|---|---|---|
| 后续评估（若立项则改表） | `run_mode` 如果要落库并可查询 | 本轮 deferred；只有进入筛选 / 报表 / 审计检索需求时，才考虑新增列或新增命令表字段 |
| 后续评估（若立项则改表） | 物理重命名 `publish_attempt` / `run_seq` / `retry_count` / `job_code` / `workflow_code` / `worker_code` / `worker_group` | 本轮 freeze；只有单独立项时才考虑，且会连带改 migration、mapper、测试数据 |
| 只改代码 | `run_mode` 的运行时意图收口 | 已通过 Launch / retry / recover / compensate 链路写入 `run_mode`，当前不改表 |
| 只改代码 | `PipelineContext` -> `ExecutionContext` | 这个项已经完成，不需要改表；后续只需保持类型引用和文档口径继续收口 |
| 只改代码 | `jobCode` / `pipelineCode` / `flowCode` 的主名收敛 | 数据库里已经存在 `job_code`、`workflow_code` 等字段，优先统一对象模型和映射层口径 |
| 只改代码 | `retry_count` / `attemptNo` / `publishAttempt` / `run_seq` 的语义分层 | 这些字段本身已经存在，重点是把“业务重试次数”和“执行尝试序号”区分清楚 |
| 只改代码 | `workerId` / `workerCode` / `workerGroup` | 现有表字段已经足够，主要修正日志、DTO、上下文和调用边界 |
| 只改文档 | `Step` / `Stage` | 这是语义边界问题，边界已在 [core-model.md](./core-model.md) 固化，本文只保留引用 |
| 只改文档 | `CompensationSubmitCommand` / `ApprovalCommand` | 命令边界问题，边界已在 [core-model.md](./core-model.md) 固化，本文只保留引用 |

### 7.2 需要改表的项

#### `run_mode`

- 现状：`core-model.md` 已将其定义为上下文意图，而不是状态；当前代码已在运行时 payload / worker context 中统一写入 `run_mode`。
- 当前判断：现在不做是低风险且合理的；本轮统一口径已经达到目标，不需要把数据库拉进来。
- 查询面现状：当前真正已有查询面是命令侧，而不是主运行态；控制台已有 [`ApprovalCommandQuery.java`](/Users/dengchao/Downloads/file-batch-system/batch-console-api/src/main/java/com/example/batch/console/domain/query/ApprovalCommandQuery.java) 和 [`ApprovalCommandMapper.xml`](/Users/dengchao/Downloads/file-batch-system/batch-console-api/src/main/resources/mapper/ApprovalCommandMapper.xml)。
- 现有承载：[`V27__approval_command.sql`](/Users/dengchao/Downloads/file-batch-system/db/migration/V27__approval_command.sql) 已有 `payload_json` 可承载上下文；[`V13__create_compensation_and_step_runtime.sql`](/Users/dengchao/Downloads/file-batch-system/db/migration/V13__create_compensation_and_step_runtime.sql) 的 `compensation_command` 没有统一上下文 JSON，如果未来真要查，更适合新增显式列。
- 审计边界：[`V7__create_ops_tables.sql`](/Users/dengchao/Downloads/file-batch-system/db/migration/V7__create_ops_tables.sql) 的 `job_execution_log` 和 [`V6__create_file_tables.sql`](/Users/dengchao/Downloads/file-batch-system/db/migration/V6__create_file_tables.sql) 的 `file_audit_log` 可以承载审计信息，但不建议作为主查询面。
- 何时改表：只有当产品要求按 `run_mode` 做筛选、统计、报表或审计检索时，才进入正式落库评估。

建议落点：
- 临时排查：优先使用 `payload_json` 或审计 JSON，不为一次性检索提前加列。
- 正式查询面：优先考虑 `batch.approval_command`、`batch.compensation_command` 或单独的审计 / trace 表。
- 避免扩散：不要优先把 `run_mode` 加进 `job_instance`、`job_task` 这类主状态表。

#### 物理列统一重命名

以下字段已经存在于数据库中，且被多个 mapper 直接引用：

- `publish_attempt`：见 [V7__create_ops_tables.sql](/Users/dengchao/Downloads/file-batch-system/db/migration/V7__create_ops_tables.sql#L63) 和 [OutboxEventMapper.xml](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/resources/mapper/OutboxEventMapper.xml)
- `run_seq`：见 [V5__create_runtime_tables.sql](/Users/dengchao/Downloads/file-batch-system/db/migration/V5__create_runtime_tables.sql#L124) 和 [V6__create_file_tables.sql](/Users/dengchao/Downloads/file-batch-system/db/migration/V6__create_file_tables.sql#L106)
- `retry_count`：见 [V5__create_runtime_tables.sql](/Users/dengchao/Downloads/file-batch-system/db/migration/V5__create_runtime_tables.sql#L59) 、[V7__create_ops_tables.sql](/Users/dengchao/Downloads/file-batch-system/db/migration/V7__create_ops_tables.sql#L21) 和 [V13__create_compensation_and_step_runtime.sql](/Users/dengchao/Downloads/file-batch-system/db/migration/V13__create_compensation_and_step_runtime.sql#L25)
- `job_code` / `workflow_code` / `worker_code` / `worker_group`：分别分布在 [V4__create_definition_tables.sql](/Users/dengchao/Downloads/file-batch-system/db/migration/V4__create_definition_tables.sql#L5) 、[V3__create_config_tables.sql](/Users/dengchao/Downloads/file-batch-system/db/migration/V3__create_config_tables.sql#L99) 和 [V5__create_runtime_tables.sql](/Users/dengchao/Downloads/file-batch-system/db/migration/V5__create_runtime_tables.sql#L25)

当前判断：
- 这项现在不建议做，而且风险明显高于收益；本轮明确 freeze，不进入执行范围。
- 风险不只在“改几张表”，而在于这些列同时散在 schema、MyBatis XML、测试种子 SQL、系统测试数据和文档 SQL 里。
- 迁移治理：平台 DDL 以 `db/migration/` 为唯一源；[`sql-script-usage-scenarios.md`](../sql/sql-script-usage-scenarios.md) 与 [`docs/sql/flyway/README.md`](../sql/flyway/README.md) 为说明；运行侧另有 [`schema.sql`](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/resources/schema.sql)（勿与 Flyway 重复演进同一结构）。
- 在这种前提下硬做 rename，最容易出现“文档改了、测试改了、真实迁移没改全”的分叉。

如果要做真正的物理列重命名，需要同时处理：
- Flyway migration
- MyBatis mapper XML
- JPA / record / entity 映射
- 测试种子 SQL
- 查询索引和唯一约束引用

如果未来必须推进：
- 按独立迁移项目立项，不和当前命名收口混做。
- 采用“新增列 / 兼容读写 / 回填 / 切换 / 清理”的迁移路径，不建议直接做一次性 `rename column`。

### 7.3 已完成代码项归档

#### `run_mode`

这个项已经按“只改代码、不改表”完成。当前 `run_mode` 统一由运行时链路写入，覆盖了正常 launch、系统 retry、lease reclaim recover 和补偿 redispatch 几条主路径；如未来要支持筛选 / 报表，再单独评估落库。

文件级范围：
- [batch-common/src/main/java/com/example/batch/common/enums/RunMode.java](/Users/dengchao/Downloads/file-batch-system/batch-common/src/main/java/com/example/batch/common/enums/RunMode.java)
- [batch-common/src/main/java/com/example/batch/common/context/RunModeSupport.java](/Users/dengchao/Downloads/file-batch-system/batch-common/src/main/java/com/example/batch/common/context/RunModeSupport.java)
- [batch-orchestrator/src/main/java/com/example/batch/orchestrator/service/DefaultLaunchService.java](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/com/example/batch/orchestrator/service/DefaultLaunchService.java)
- [batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/DefaultRetryGovernanceService.java](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/DefaultRetryGovernanceService.java)
- [batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/lease/PartitionLeaseReclaimScheduler.java](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/lease/PartitionLeaseReclaimScheduler.java)
- [batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/DefaultFileGovernanceService.java](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/DefaultFileGovernanceService.java)
- [batch-worker-core/src/main/java/com/example/batch/worker/core/infrastructure/DefaultTaskExecutionWrapper.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-core/src/main/java/com/example/batch/worker/core/infrastructure/DefaultTaskExecutionWrapper.java)

#### `PipelineContext` -> `ExecutionContext`

这个项已经完成，仓库内只保留 `ExecutionContext` 主名，不需要动 schema。`PipelineContext` 仅保留为历史检索词，不再保留独立类型。实际改动集中在以下文件：
- Java 类型名
- interface 实现
- adapter / executor 引用
- 注释和测试断言

文件级范围：
- [batch-orchestrator/src/main/java/com/example/batch/orchestrator/domain/pipeline/ExecutionContext.java](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/com/example/batch/orchestrator/domain/pipeline/ExecutionContext.java)
- [batch-worker-core/src/main/java/com/example/batch/worker/core/support/ExecutionContext.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-core/src/main/java/com/example/batch/worker/core/support/ExecutionContext.java)
- [batch-worker-export/src/main/java/com/example/batch/worker/exports/infrastructure/ExportStepExecutionAdapter.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-export/src/main/java/com/example/batch/worker/exports/infrastructure/ExportStepExecutionAdapter.java)
- [batch-worker-import/src/main/java/com/example/batch/worker/imports/infrastructure/ImportStepExecutionAdapter.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-import/src/main/java/com/example/batch/worker/imports/infrastructure/ImportStepExecutionAdapter.java)

#### `jobCode` / `pipelineCode` / `flowCode`

数据库里并不是三个独立概念，而是同一业务边界在不同表里的投影。建议先收敛代码层主名，再保持现有列名兼容：
- `batch.job_definition.job_code`
- `batch.pipeline_definition.job_code`
- `batch.workflow_definition.workflow_code`

说明：
- 当前仍保留少量 `pipelineCode` 兼容访问器或兼容读取，这是刻意保留的兼容层，不视为漏改。
- 典型落点见 [batch-orchestrator/src/main/java/com/example/batch/orchestrator/domain/pipeline/ExecutionContext.java](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/com/example/batch/orchestrator/domain/pipeline/ExecutionContext.java)、[batch-orchestrator/src/main/java/com/example/batch/orchestrator/domain/pipeline/PipelineDefinitionModel.java](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/com/example/batch/orchestrator/domain/pipeline/PipelineDefinitionModel.java)、[batch-worker-core/src/main/java/com/example/batch/worker/core/support/AbstractPipelineStepExecutionAdapter.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-core/src/main/java/com/example/batch/worker/core/support/AbstractPipelineStepExecutionAdapter.java)。

文件级范围：
- [batch-worker-core/src/main/java/com/example/batch/worker/core/infrastructure/PlatformFileRuntimeRepository.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-core/src/main/java/com/example/batch/worker/core/infrastructure/PlatformFileRuntimeRepository.java)
- [batch-console-api/src/main/java/com/example/batch/console/web/response/ConsoleFilePipelineResponse.java](/Users/dengchao/Downloads/file-batch-system/batch-console-api/src/main/java/com/example/batch/console/web/response/ConsoleFilePipelineResponse.java)
- [batch-console-api/src/main/java/com/example/batch/console/support/WorkflowExcelImportStore.java](/Users/dengchao/Downloads/file-batch-system/batch-console-api/src/main/java/com/example/batch/console/support/WorkflowExcelImportStore.java)
- [batch-console-api/src/main/java/com/example/batch/console/support/JobDefinitionExcelImportStore.java](/Users/dengchao/Downloads/file-batch-system/batch-console-api/src/main/java/com/example/batch/console/support/JobDefinitionExcelImportStore.java)
- [batch-console-api/src/main/resources/mapper/WorkflowDefinitionMapper.xml](/Users/dengchao/Downloads/file-batch-system/batch-console-api/src/main/resources/mapper/WorkflowDefinitionMapper.xml)
- [batch-console-api/src/main/resources/mapper/JobDefinitionMapper.xml](/Users/dengchao/Downloads/file-batch-system/batch-console-api/src/main/resources/mapper/JobDefinitionMapper.xml)
- [batch-orchestrator/src/main/resources/mapper/JobInstanceMapper.xml](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/resources/mapper/JobInstanceMapper.xml)
- [batch-orchestrator/src/main/resources/mapper/WorkflowNodeMapper.xml](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/resources/mapper/WorkflowNodeMapper.xml)
- [batch-orchestrator/src/main/resources/mapper/CompensationCommandMapper.xml](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/resources/mapper/CompensationCommandMapper.xml)

#### `retry_count` / `attemptNo` / `publishAttempt` / `run_seq`

建议统一口径如下：
- DB 里的 `retry_count` 保留作“业务重试次数”
- 消息里的 `attemptNo` 保留作“消息尝试序号”
- outbox 的 `publish_attempt` 保留作“发布尝试序号”
- 节点/步骤的 `run_seq` 保留作“同一节点的执行序号”

这类问题优先改 mapper、DTO、日志和文档，不直接改表。

文件级范围：
- [batch-orchestrator/src/main/resources/mapper/OutboxEventMapper.xml](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/resources/mapper/OutboxEventMapper.xml)
- [batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/engine/DefaultScheduleForwarder.java](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/engine/DefaultScheduleForwarder.java)
- [batch-orchestrator/src/main/resources/mapper/WorkflowNodeRunMapper.xml](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/resources/mapper/WorkflowNodeRunMapper.xml)
- [batch-orchestrator/src/main/resources/mapper/JobPartitionMapper.xml](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/resources/mapper/JobPartitionMapper.xml)
- [batch-orchestrator/src/main/resources/mapper/JobStepInstanceMapper.xml](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/resources/mapper/JobStepInstanceMapper.xml)
- [batch-console-api/src/main/resources/mapper/WorkflowNodeRunMapper.xml](/Users/dengchao/Downloads/file-batch-system/batch-console-api/src/main/resources/mapper/WorkflowNodeRunMapper.xml)
- [batch-console-api/src/main/resources/mapper/FilePipelineStepRunMapper.xml](/Users/dengchao/Downloads/file-batch-system/batch-console-api/src/main/resources/mapper/FilePipelineStepRunMapper.xml)
- [batch-worker-core/src/main/resources/mapper/PlatformFileRuntimeMapper.xml](/Users/dengchao/Downloads/file-batch-system/batch-worker-core/src/main/resources/mapper/PlatformFileRuntimeMapper.xml)

#### `workerId` / `workerCode` / `workerGroup`

现有表结构已经表达了足够信息：
- `worker_code` 是注册实体编码
- `worker_group` 是调度/消费分组
- `workerId` 更多是运行时实例标识或日志字段

建议先统一接口、DTO、日志字段名，再决定是否需要进一步落库。

文件级范围：
- [batch-worker-core/src/main/java/com/example/batch/worker/core/domain/WorkerRegistration.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-core/src/main/java/com/example/batch/worker/core/domain/WorkerRegistration.java)
- [batch-worker-core/src/main/java/com/example/batch/worker/core/infrastructure/DefaultHeartbeatService.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-core/src/main/java/com/example/batch/worker/core/infrastructure/DefaultHeartbeatService.java)
- [batch-worker-core/src/main/java/com/example/batch/worker/core/infrastructure/DefaultWorkerLifecycleManager.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-core/src/main/java/com/example/batch/worker/core/infrastructure/DefaultWorkerLifecycleManager.java)
- [batch-worker-core/src/main/java/com/example/batch/worker/core/infrastructure/HttpTaskExecutionClient.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-core/src/main/java/com/example/batch/worker/core/infrastructure/HttpTaskExecutionClient.java)
- [batch-worker-core/src/main/java/com/example/batch/worker/core/infrastructure/HttpWorkerRegistryClient.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-core/src/main/java/com/example/batch/worker/core/infrastructure/HttpWorkerRegistryClient.java)
- [batch-worker-core/src/main/java/com/example/batch/worker/core/infrastructure/ActiveTaskLeaseRegistry.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-core/src/main/java/com/example/batch/worker/core/infrastructure/ActiveTaskLeaseRegistry.java)
- [batch-worker-core/src/main/java/com/example/batch/worker/core/infrastructure/WorkerRuntimeState.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-core/src/main/java/com/example/batch/worker/core/infrastructure/WorkerRuntimeState.java)
- [batch-worker-export/src/main/java/com/example/batch/worker/exports/domain/ExportJobContext.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-export/src/main/java/com/example/batch/worker/exports/domain/ExportJobContext.java)
- [batch-worker-import/src/main/java/com/example/batch/worker/imports/domain/ImportJobContext.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-import/src/main/java/com/example/batch/worker/imports/domain/ImportJobContext.java)
- [batch-common/src/main/java/com/example/batch/common/logging/StructuredLogField.java](/Users/dengchao/Downloads/file-batch-system/batch-common/src/main/java/com/example/batch/common/logging/StructuredLogField.java)
- [batch-common/src/main/java/com/example/batch/common/model/WorkerRouteModel.java](/Users/dengchao/Downloads/file-batch-system/batch-common/src/main/java/com/example/batch/common/model/WorkerRouteModel.java)
- [batch-console-api/src/main/resources/mapper/WorkerRegistryMapper.xml](/Users/dengchao/Downloads/file-batch-system/batch-console-api/src/main/resources/mapper/WorkerRegistryMapper.xml)
- [batch-console-api/src/main/java/com/example/batch/console/web/query/WorkerRegistryQueryRequest.java](/Users/dengchao/Downloads/file-batch-system/batch-console-api/src/main/java/com/example/batch/console/web/query/WorkerRegistryQueryRequest.java)
- [batch-orchestrator/src/main/java/com/example/batch/orchestrator/domain/scheduler/ResourceSchedulingRequest.java](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/com/example/batch/orchestrator/domain/scheduler/ResourceSchedulingRequest.java)
- [batch-orchestrator/src/main/java/com/example/batch/orchestrator/domain/scheduler/ResourceSchedulingDecision.java](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/com/example/batch/orchestrator/domain/scheduler/ResourceSchedulingDecision.java)

### 7.4 已固化文档项归档

#### `Step` / `Stage`

数据库里同时存在 `step_code`、`stage_code`、`step_status` 等字段，但它们不构成一套新的持久化模型。边界定义以 [core-model.md](/Users/dengchao/Downloads/file-batch-system/docs/architecture/core-model.md) 3.6 为准；本文只保留候选清单和落点，不重复展开。

#### 命令对象边界

`CompensationSubmitCommand` / `ApprovalCommand` 不需要先改表，先把“谁触发、何时执行、是否审批、是否可重放”写清楚，避免误把命令当状态或运行态对象。边界定义以 [core-model.md](/Users/dengchao/Downloads/file-batch-system/docs/architecture/core-model.md) 6.2 / 6.4 为准；本文只保留候选和落点。

### 7.5 剩余工作建议

1. `run_mode` 只有在产品明确提出筛选、统计、报表或审计检索需求时，才进入落库评估。
2. 在此之前，继续维持“上下文意图”定义，优先使用 `payload_json` 或审计 JSON 承载，不扩散成状态字段。
3. 物理列统一命名保持冻结；若未来必须推进，单独评估 migration、mapper、索引、回填和切换成本。
