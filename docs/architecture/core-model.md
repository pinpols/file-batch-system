# 核心模型统一口径

> 本文档是批量调度平台的**单一权威模型文档**。
> 它统一定义四组最容易被分散理解的概念：**统一实例模型、统一状态模型、统一上下文模型、统一恢复模型**。
>  
> 阅读顺序建议：先看 [architecture-truth.md](./architecture-truth.md)，再看本文档，最后回看 [maturity-assessment.md](./maturity-assessment.md) 和 [scalability-assessment.md](./scalability-assessment.md)。

## 1. 这份文档解决什么问题

当前项目里，核心概念分散在多个地方：
- `architecture-truth.md` 讲系统事实
- `maturity-assessment.md` 讲落地状态与成熟度
- `scalability-assessment.md` 讲承载力差距和改造路线
- ADR 讲局部决策

问题不在于信息少，而在于**同一个词在不同文档里容易被不同团队理解成不同东西**。  
本文档要做的是：
- 把名词统一
- 把边界统一
- 把状态和恢复语义统一
- 把“什么是实体、什么是属性、什么是上下文、什么是动作”说清楚

### 1.1 当前决议摘要

已定稿：
- `ExecutionContext` 是统一上下文主名；`PipelineContext` 只保留为历史检索词
- `jobCode` 是统一主名；`pipelineCode` / `flowCode` 只保留兼容口径，少量兼容访问器 / 兼容读取不视为漏改
- `retry_count` / `attemptNo` / `publishAttempt` / `run_seq` 已按层级分开
- `run_mode` 已作为运行时上下文意图落地，不进入主状态表；当前查询面继续由命令 / 审计承接，如需筛选 / 报表再单独评估写入数据库
- `workerId` / `workerCode` / `workerGroup` 已明确职责边界
- `Step` / `Stage` 的语义边界已定稿
- `CompensationSubmitCommand` / `ApprovalCommand` 的命令边界已定稿
- 数据库物理列重命名不进入本轮统一口径范围，当前 schema 口径冻结

## 2. 四个模型总览

| 模型 | 关注点 | 统一结论 |
|---|---|---|
| 统一实例模型 | 哪些对象算“执行实例” | `job_instance` 是根，`workflow_run` / `job_partition` / `job_task` / `job_step_instance` 是不同层级的执行镜像或子实例 |
| 统一状态模型 | 状态值怎么解释 | 状态只描述当前生命周期位置，不承载恢复语义；终态不能直接回到非终态 |
| 统一上下文模型 | 运行时信息怎么传递 | 上下文分成“稳定主键 + 可变 attributes bag”；`run_mode` 是上下文意图，不是状态 |
| 统一恢复模型 | retry / rerun / recover / compensate 怎么区分 | 这四个词必须分开：系统重试、人工重跑、故障恢复、业务补偿是四种不同动作 |

---

## 3. 统一实例模型

### 3.1 实例层级

推荐统一成下面这条链：

`job_instance` → `workflow_run` → `job_partition` → `job_task` → `job_step_instance`

含义如下：

| 对象 | 角色 | 说明 |
|---|---|---|
| `job_instance` | 根实例 | 一次业务触发的根对象，承载 tenant / jobCode / bizDate / requestId / dedup 等根信息 |
| `workflow_run` | 流程运行镜像 | 该 job 在工作流语义下的一次运行视图，负责串起 DAG 节点推进 |
| `job_partition` | 分片执行单元 | 调度与并发控制的最小派发粒度，承载 worker 归属、租约、重试次数 |
| `job_task` | 任务执行单元 | Worker 实际领取和回报的执行单元，通常是一个分片下的单次工作项 |
| `job_step_instance` | 步骤审计镜像 | 面向 UI、审计和可视化的步骤级镜像，不应和 `job_task` 混为一谈 |

### 3.2 统一原则

1. `job_instance` 是业务根，不是 worker 运行的最小单位。
2. `workflow_run` 是流程视角，不是 worker 视角。
3. `job_partition` 是调度视角，不是业务结果视角。
4. `job_task` 是执行视角，不是 DAG 节点视角。
5. `job_step_instance` 是展示与审计视角，不是调度核心视角。

### 3.3 attempt 不是一等实体

`attempt` 在当前系统里应该被视为**执行次数或尝试序号**，而不是独立的核心业务实体。

当前代码里，尝试次数分散在不同层：
- `retry_count`
- `publishAttempt`
- `run_seq`
- `attemptNo`

这些字段可以表达“第几次”，但不能代表“一个独立业务对象”。

这里需要区分两层：
- 在主运行态模型里，`attempt` 不是 `job_instance`、`job_partition`、`job_task`、`job_step_instance` 之外的另一层核心对象。
- 在 outbox / delivery 运维审计层，可以存在附属记录来描述某次发布或投递尝试，例如 `event_outbox_retry`、`event_delivery_log`。

换句话说：
- `attempt` 不参与主运行态建模，不作为调度、执行、补偿的核心聚合。
- `attempt` 允许作为附属日志或审计记录存在，用来追踪发布/投递过程中的“第几次”及结果。

### 3.4 三层联动的正确理解

所谓 `step-partition-task` 联动，正确口径不是“三个平行实体”，而是：

1. `job_step_instance` 表示“业务上这个步骤执行到什么位置”
2. `job_partition` 表示“这一步被拆成哪些派发单元”
3. `job_task` 表示“worker 实际拿到的工作项”

换句话说：
- `step` 负责语义
- `partition` 负责并发与路由
- `task` 负责落地执行

### 3.5 代码对照

当前代码中已经存在这些实体：
- [`JobInstanceEntity.java`](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/entity/JobInstanceEntity.java)
- [`WorkflowRunEntity.java`](../../batch-common/src/main/java/io/github/pinpols/batch/common/persistence/entity/WorkflowRunEntity.java)
- [`JobPartitionEntity.java`](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/entity/JobPartitionEntity.java)
- [`JobTaskEntity.java`](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/entity/JobTaskEntity.java)
- [`JobStepInstanceEntity.java`](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/entity/JobStepInstanceEntity.java)
- [`WorkflowNodeRunEntity.java`](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/entity/WorkflowNodeRunEntity.java)

### 3.6 Partition 切分契约（orchestrator → worker）

**机制 vs 策略分离**：orchestrator 决定"派多少 partition + 给每个 partition 一个号牌"，worker 决定"我拿到号牌后干哪部分"（按文件行 / 按字节 range / 按业务键 hash / 按机构号等）。平台不强加切分维度——这是有意识的"机制（数量决策 + lease + 重试 + 状态机）vs 策略（怎么读 / 怎么过滤）"分离，与 K8s scheduler / Spring Batch Partitioner 同构。

#### 3.6.1 三个运行时字段的端到端流向

```
orchestrator                                                    worker
─────────────────                                               ─────────
job_partition.partition_no  ──┐
job_partition.partition_key ──┼─claim()─→ EffectiveTaskConfig
job_instance.expected_       ──┘                ↓
       partition_count                     PulledTask
                                                ↓
                                       DefaultTaskExecutionWrapper
                                                ↓
                                      context.attributes:
                                        PARTITION_NO     = K (1-based)
                                        PARTITION_COUNT  = N
                                        PARTITION_KEY    = "<jobCode>:<bizDate>:<K>"
                                                ↓
                                      worker step / plugin
                                      (ParseStep / ProcessComputePlugin / ...)
                                                ↓
                                      自行决定切什么:
                                       - lineNo % N == K-1   (默认 ParseStep)
                                       - hash(branchCode) % N == K-1
                                       - id BETWEEN ... AND ...
                                       - 按 input_snapshot 字段过滤
```

**字段语义**（`PipelineRuntimeKeys`）：

| Key | 类型 | 含义 |
|---|---|---|
| `PARTITION_NO` | Integer (1-based) | 当前 task 的分区序号；不分片场景为 `1` |
| `PARTITION_COUNT` | Integer | 本次 job_instance 的分区总数；`<= 1` 视为不分片 |
| `PARTITION_KEY` | String | 业务标识，默认 `jobCode:bizDate:partitionNo`，业务可在 plan-build 时覆盖为机构号 / hash 桶等 |

#### 3.6.2 默认 ParseStep 行 mod 切分

`batch-worker-import` 的 `ParseStep` 默认 partition-aware：format parser 写完 NDJSON staging 后，post-filter 按 `lineNo % PARTITION_COUNT == PARTITION_NO - 1` 流式重写，下游 VALIDATE/LOAD 只看本 partition 数据。

- **开关**：`template_config.partition_aware_parse=false` 关闭（默认开）
- **统计口径**：`totalCount` = 文件原始行数（不变，全 partition 视角）；`parsedCount` = 本 partition 实际处理行数（切分时变小）
- **零侵入回退**：`PARTITION_COUNT <= 1` / 字段缺失 / 开关关闭 / `partitionNo` 越界 → 直通，与历史行为等价

#### 3.6.3 业务自定义切分维度

需要按机构号 / 业务键 / 数据范围切时：

1. **plan-build 阶段**覆盖 `partition_key`：在 `DefaultSchedulePlanBuilder` 自定义 partition_key 生成（默认 `jobCode:bizDate:partitionNo`，可写成 `branchCode=BJ` / `range=[10000,19999]` 等）
2. **partition 创建时**写 `input_snapshot JSONB`（`job_partition.input_snapshot`）：`{"branchCode":"BJ","dateRange":"2026-04"}`
3. **worker step**自定义 plugin 读 `PARTITION_KEY` / `input_snapshot` 自行路由数据范围；关闭默认行 mod 切分（`partition_aware_parse=false`）

**反例**：不要在 `PartitionCountResolver` 里塞业务切分逻辑——它只决定数量，不决定字段。详见 [`ADR-005-partition-count-resolver-chain.md`](./adr/ADR-005-partition-count-resolver-chain.md)。

### 3.7 Step / Stage 边界

`Step` 和 `Stage` 不是同义词，不能互换。

推荐口径如下：

| 术语 | 角色 | 典型对象 | 责任 |
|---|---|---|---|
| `Step` | 领域步骤定义 | orchestrator 的 `StepDefinition`、`StepResult`、`JobStepInstance` | 描述业务处理链路中的逻辑步骤，偏编排和审计 |
| `Stage` | Worker 执行阶段 | worker-core 的 `ImportStage`、`ExportStage`、`DispatchStage`、`StageExecutionResult` | 描述单个 worker 内部的执行阶段，偏实现和运行时 |

约束：

1. `Step` 偏 orchestrator 的编排视角，不负责 worker 内部如何拆分执行。
2. `Stage` 偏 worker 的执行视角，不承担 DAG 拓扑或编排规则。
3. `job_step_instance` 记录的是 `Step` 级审计镜像，不是 `Stage` 的持久化替身。
4. `stage_code` 只是 worker 侧执行阶段枚举，不要把它上升成新的业务实体名。
5. 文档和代码评审里如果同时出现 `Step` 和 `Stage`，必须先确认语境是“编排”还是“执行”，不能默认等价。

---

## 4. 统一状态模型

### 4.1 状态只描述生命周期位置

状态模型只回答一个问题：

> 这个对象现在处于生命周期的哪一段？

状态**不**回答这些问题：
- 为什么重试
- 谁触发恢复
- 是否需要补偿
- 业务语义是否变更

恢复语义、触发者、原因、审批信息应放在：
- command 表
- audit 表
- metadata
- trace / context

### 4.2 当前统一状态口径

#### WorkflowRunStatus

当前代码定义：
- `CREATED`
- `RUNNING`
- `SUCCESS`
- `FAILED`
- `TERMINATED`

含义：
- `CREATED`：已建档，未真正推进
- `RUNNING`：流程已开始推进
- `SUCCESS`：所有节点完成且成功
- `FAILED`：流程失败终止
- `TERMINATED`：被人工或系统终止

#### StepInstanceStatus

当前代码定义：
- `CREATED`
- `WAITING`
- `READY`
- `RUNNING`
- `RETRYING`
- `SUCCESS`
- `FAILED`
- `CANCELLED`
- `TERMINATED`

含义：
- `CREATED`：步骤镜像已创建
- `WAITING`：等待前置条件
- `READY`：可以派发
- `RUNNING`：正在执行
- `RETRYING`：失败后等待重试或再次认领
- `SUCCESS`：成功结束
- `FAILED`：失败结束
- `CANCELLED`：人工或系统取消
- `TERMINATED`：被终止

#### TaskStatus

当前代码定义：
- `CREATED`
- `READY`
- `RUNNING`
- `SUCCESS`
- `FAILED`
- `CANCELLED`
- `TERMINATED`

含义：
- `CREATED`：任务记录已创建
- `READY`：已具备领取条件
- `RUNNING`：worker 已开始执行
- `SUCCESS`：任务完成成功
- `FAILED`：任务失败
- `CANCELLED`：取消执行
- `TERMINATED`：任务被终止

### 4.3 状态迁移原则

1. 终态不直接回到非终态。
2. `RETRYING` 不是终态。
3. 状态变更必须有前置条件，不能“看起来像对就写进去”。
4. `workflow_run`、`step_instance`、`task` 三层状态不能随意共用同一枚举值的业务含义。
5. 不要把恢复动作伪装成状态回滚。

### 4.4 迁移与恢复的边界

状态迁移只处理“生命周期推进”：
- `READY -> RUNNING`
- `RUNNING -> SUCCESS / FAILED`
- `FAILED -> RETRYING`
- `READY / RUNNING -> CANCELLED / TERMINATED`

如果是“重新做一遍”，那已经不是普通状态迁移，而是恢复模型的一部分。

---

## 5. 统一上下文模型

### 5.1 上下文是什么

上下文是运行时输入，不是状态。

统一上下文应该由两部分组成：
- **稳定主键字段**
- **可变 attributes bag**

### 5.2 当前代码里的上下文形态

当前仓库已经存在两种相关形态：

1. Orchestrator 侧的 [`ExecutionContext`](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/pipeline/ExecutionContext.java)
2. Worker core 侧的 [`ExecutionContext`](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/ExecutionContext.java)
3. 旧名 `PipelineContext` 仅保留为兼容语义和历史检索词，仓库里不再新增该类型

它们的共同点是：
- 都携带 tenant / job / worker 等基础信息
- 都依赖可变属性袋传递阶段间数据

命名约束：
- 新文档、新 DTO、新接口说明统一使用 `ExecutionContext`
- `PipelineContext` 只用于检索旧资料或解释历史改动，不再作为新的类型名或字段名
- 新代码和新文档默认使用 `jobCode`；少量 `pipelineCode` 兼容访问器或兼容读取只用于承接历史调用和旧 payload，不再继续扩散

兼容层说明：
- 当前仍保留少量 `pipelineCode` 兼容入口，这是刻意保留的兼容层，不视为漏改
- 典型落点见 [`ExecutionContext.java`](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/pipeline/ExecutionContext.java)、[`AbstractPipelineStepExecutionAdapter.java`](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/AbstractPipelineStepExecutionAdapter.java)（原 `PipelineDefinitionModel.java` 为零引用死代码，2026-07-09 已删除）

### 5.3 统一上下文的推荐结构

建议把上下文理解成这三层：

| 层级 | 内容 | 是否稳定 |
|---|---|---|
| 请求头 | tenantId、jobCode、traceId、bizDate、requestId | 稳定 |
| 执行标识 | jobInstanceId、workflowRunId、partitionId、taskId、workerId | 稳定 |
| attributes | fileId、templateConfig、channelConfig、stepDefinitions、snapshot 等 | 可变 |

### 5.4 `run_mode` 的统一定义

`run_mode` 目前不是一等实体字段，建议把它定义为**上下文意图**，而不是状态。

推荐取值：
- `NORMAL`：正常执行
- `RETRY`：系统重试
- `RERUN`：人工重跑
- `RECOVER`：故障恢复
- `COMPENSATE`：业务补偿

推荐规则：
- `run_mode` 只出现在请求上下文、命令、审计里
- `run_mode` 不要写进主状态字段
- `run_mode` 不能用来替代 `status`

当前实现：
- 运行时上下文统一使用键 `run_mode`
- 兼容读取旧别名 `runMode`，但新 payload / context 一律写 `run_mode`
- Launch / retry / recover / compensate 链路都会把 `run_mode` 写入 payload 和 worker context
- 应用日志通过 MDC 输出 `runMode` 字段，便于在 Loki / ELK 中直接检索运行意图
- 当前不新增数据库字段；只有在需要按 `run_mode` 查询、统计、审计时才评估写入数据库

数据库层面的当前决议：
- 本轮继续维持 deferred，不把 `run_mode` 扩散进 `job_instance`、`job_task` 这类主状态表。
- 当前真正已有查询面是命令侧，不是主运行态；例如 `approval_command` 已可承接控制台查询和审计检索。
- 临时排查优先使用命令载荷或审计 JSON，不为一次性检索提前加列。
- 只有当控制台 / API 明确需要按 `run_mode` 做筛选、统计、报表或审计检索时，才进入正式写入数据库评估。
- 如果未来需要写入数据库，优先落在命令表或审计表，不优先落在主状态表。
- 物理列统一重命名不属于本轮目标，保持现有 schema 兼容，不顺手把数据库拉进当前命名收敛。

### 5.5 上下文继承规则

1. 上下文可以向下传递，但不要把子阶段的临时值回写成父级稳定字段。
2. `attributes` 是可变袋，但键名必须受控，统一由 `PipelineRuntimeKeys` 承载。
3. 新增键时要优先复用公共 key，不要各模块自己发明同义键。
4. 任何可审计信息都应该同时进入日志、command 或 audit，而不是只放在内存上下文里。

### 5.6 代码对照

- [`PipelineRuntimeKeys.java`](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PipelineRuntimeKeys.java)
- [`AbstractPipelineStepExecutionAdapter.java`](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/AbstractPipelineStepExecutionAdapter.java)
- [`AbstractStageExecutor.java`](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/AbstractStageExecutor.java)

---

## 6. 统一恢复模型

### 6.1 四个词必须分开

| 词 | 含义 | 谁触发 | 是否改变业务语义 |
|---|---|---|---|
| retry | 系统对同一执行单元再次尝试 | 系统 | 不改变 |
| rerun | 人工对同一业务范围重新跑一遍 | 人工 / 控制台 | 可能不改变，也可能重算 |
| recover | 从故障/中断中继续推进 | 系统 | 不改变 |
| compensate | 对已发生结果做纠正或反向操作 | 人工 / 审批 / 规则引擎 | 会改变 |

### 6.2 推荐定义

#### retry

- 同一业务意图下的再次执行
- 典型对象：`job_task`、`job_partition`、`retry_schedule`
- 典型驱动：网络抖动、临时依赖不可用、可重试业务错误
- 特点：自动、受上限控制、最终会进入 `dead_letter_task` 或成功

#### rerun

- 对某个范围做一次显式重跑
- 典型对象：`job_instance`、`job_partition`、`job_step_instance`
- 典型驱动：人工修正输入数据、重新评估规则、重新生成产物
- 特点：是“再跑一遍”，不等于“简单重试”

#### recover

- 从系统中断点恢复运行
- 典型对象：worker 心跳、lease、outbox、drain、未完成任务的接管
- 典型驱动：进程重启、节点漂移、短时故障、leader 切换
- 特点：恢复的是执行连续性，不是业务结论

#### compensate

- 对已经提交的业务结果做纠正
- 典型对象：`compensation_command`、`approval_command`
- 典型驱动：人工审批、业务撤销、纠偏、审计要求
- 特点：必须可审计，通常比 retry / recover 更重

#### compensation submit / approval

`CompensationSubmitCommand` 和 `ApprovalCommand` 不是状态字段，也不是运行态实体。它们是两类命令：

- `CompensationSubmitCommand`：表达“我要发起一次补偿”的意图，记录触发人、目标范围、原因和上下文
- `ApprovalCommand`：表达“我批准或拒绝这次补偿”的决策，记录审批人、结论和审批意见

约束：

1. 命令对象只描述一次动作请求，不承载生命周期状态。
2. 审批结果和执行结果应分别进入审计或命令表，不要塞回命令 DTO 当作持久状态。
3. 如果要表达“已提交”“已审批”“已执行”，应在状态模型或审计模型里单独建模，不能靠命令字段隐式表达。
4. 这两个命令的职责是可追溯、可重放、可审计，不是可运行的 worker 输入模型。

### 6.3 推荐决策树

1. 如果只是同一执行单元失败后再次尝试，选 `retry`。
2. 如果是运营人员或控制台明确要求整段重跑，选 `rerun`。
3. 如果只是进程/网络/租约恢复，选 `recover`。
4. 如果要改变既有业务结果，选 `compensate`。

### 6.4 代码和表的映射

这一节只用于帮助定位实现，不代表同一行里的实现天然语义等价。实现上即使复用了同一服务，领域边界仍以上文定义为准。

| 模型 | 主要表 / 代码 |
|---|---|
| retry | `retry_schedule`、`dead_letter_task`、`DefaultRetryGovernanceService` |
| rerun | `job_instance`、`job_step_instance`、`DefaultCompensationService`、控制台 rerun/compensate 接口 |
| recover | worker lease / outbox / drain / heartbeat 相关代码 |
| compensate | `compensation_command`、`ApprovalWorkflowService`、`DefaultCompensationService` |

### 6.5 不要混用的反例

- 不要把 `retry` 和 `rerun` 当成同义词
- 不要把 `recover` 写成一个新的状态值
- 不要把 `compensate` 实现成普通重试
- 不要把“恢复成功”理解成“业务成功”

---

## 7. 统一术语表

| 术语 | 推荐解释 |
|---|---|
| instance | 一个业务触发的根执行对象，通常指 `job_instance` |
| run | 某一层级的运行镜像，通常指 `workflow_run` / `workflow_node_run` |
| partition | 调度并发单元，通常指 `job_partition` |
| task | worker 执行单元，通常指 `job_task` |
| step instance | 步骤审计镜像，通常指 `job_step_instance` |
| attempt | 尝试序号，不是一等实体 |
| context | 运行时输入 + attributes bag |
| mode | 触发意图，不是状态 |
| retry | 系统重试 |
| rerun | 人工重跑 |
| recover | 故障恢复 |
| compensate | 业务补偿 |

---

## 8. 统一口径要求

1. 新增设计、接口、表结构时，优先沿用本文档术语。
2. 看到 `attempt` 时，默认先问是“次数”还是“实体”。
3. 看到 `run_mode` 时，默认先问它是不是上下文意图，而不是状态。
4. 看到 `retry / rerun / recover / compensate` 时，必须先区分动作归属，再决定写入数据库表和审计路径。
5. 如果后续文档与本文档冲突，以本文档为准；如果代码与本文档冲突，以代码为准，但要补文档回写。

---

## 9. 需要同步阅读的文档

- [architecture-truth.md](./architecture-truth.md)
- [maturity-assessment.md](./maturity-assessment.md)
- [scalability-assessment.md](./scalability-assessment.md)
- [runtime-module-communication.md](./runtime-module-communication.md)
- [adr/README.md](./adr/README.md)
