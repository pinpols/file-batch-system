# 状态机汇总

> **status**: 固化（2026-05-03）  
> **scope**: job_instance / pipeline_instance / workflow_run / job_task / job_partition / outbox 状态机定义、转移规则、是否一致

## 1. 状态机一览

| 实体 | enum 类 | DB 列 | CHECK 约束 | 状态值 |
|---|---|---|---|---|
| `job_instance.instance_status` | `JobInstanceStatus` | V5:52 | ✓ | CREATED / WAITING / READY / RUNNING / PARTIAL_FAILED / SUCCESS / FAILED / CANCELLED / TERMINATED |
| `pipeline_instance.run_status` | `PipelineRunStatus` | V6:103 | ✓ | CREATED / RUNNING / SUCCESS / FAILED / **COMPENSATING** / TERMINATED |
| `workflow_run.run_status` | `WorkflowRunStatus` | V5:121 | ✓ | CREATED / RUNNING / SUCCESS / FAILED / TERMINATED |
| `workflow_node_run.node_status` | `WorkflowNodeRunStatus` | V5 | ✓ | CREATED / READY / RUNNING / SUCCESS / FAILED / SKIPPED / TERMINATED |
| `job_task.task_status` | `TaskStatus` | V5 | ✓ | CREATED / READY / RUNNING / SUCCESS / FAILED / RETRYING / CANCELLED / TERMINATED |
| `job_partition.partition_status` | `PartitionStatus` | V5 | ✓ | CREATED / READY / RUNNING / SUCCESS / FAILED / TERMINATED |
| `job_step_instance.step_status` | `StepInstanceStatus` | V13 | ✓ | CREATED / WAITING / READY / RUNNING / SUCCESS / FAILED / RETRYING / CANCELLED / TERMINATED |
| `outbox_event.publish_status` | `OutboxPublishStatus` | V21 | ✓ | NEW / PUBLISHING / PUBLISHED / FAILED / GIVE_UP |
| `trigger_outbox_event.publish_status` | `OutboxPublishStatus` | V80 | ✓ | 同上 |
| `event_outbox_retry.retry_status` | 内部 enum | V21 | ✓ | WAITING / RUNNING / SUCCESS / FAILED / EXHAUSTED / CANCELLED |
| `trigger_request.request_status` | _无 Java enum class，仅 DB CHECK 回退_ | V5 / V39 / V60 | ⚠️ DB-only | PENDING / PROCESSING / ACCEPTED / DUPLICATE / REJECTED / LAUNCHED / FORWARD_FAILED / GIVE_UP（V60 扩展，含 ADR-010 trigger 异步链路 forward retry 状态） |
| `compensation_command.command_status` | `CompensationCommandStatus` | V13 | ✓ | CREATED / RUNNING / SUCCESS / FAILED / CANCELLED |
| `worker_registry.status` | `WorkerRegistryStatus` | V7 | ✓ | ONLINE / DRAINING / OFFLINE / DECOMMISSIONED |

## 2. 不一致点（已知 + 是否要统一）

### 2.1 `pipeline_instance` 多了 `COMPENSATING` 态

**事实**：V6 创建 pipeline_instance 时枚举里有 `COMPENSATING`，job_instance / workflow_run 没有。

**⚠️ 实现状态(2026-06-16 审计澄清):`COMPENSATING` 当前是「预留态,未实现」。** 全 worker 代码无任何写 `pipeline_instance.run_status = COMPENSATING` 的路径——pipeline stage 失败**直接落 `FAILED`**,不做 stage 级反向补偿(删 biz / 删 MinIO)。枚举值 + DB CHECK 仅为前向兼容保留。**运维不应假设 pipeline 失败会自动补偿删异常数据。** (注:dispatch 域 `FileDispatchRunStatus.COMPENSATING` 是真实现的,勿混淆。)

**设想语义(若将来实现)**:pipeline 内部 stage 失败触发反向 stage 补偿,需要中间态标识"在补偿中"防止外部误判为 FAILED 终态。job_instance / workflow_run 的补偿走 `compensation_command` 独立表,不需要主状态标记。

**结论**:**保留枚举差异**(语义专属 pipeline 域);但在真正实现 stage 补偿前,文档与运维须按"未实现"对待,不得依赖该态。

### 2.2 `READY` 出现在多张表，但语义微差

| 表 | READY 语义 |
|---|---|
| job_instance | "待 worker 选拔"（DefaultWorkerSelector 还未分配 worker_code） |
| job_task / job_partition | "已分配 worker_code，待 worker CLAIM" |
| job_step_instance | "上游 step 完成，本 step 即将启动" |
| workflow_node_run | "上游 node 完成，本 node 即将启动" |

**结论**：**保持差异**。同名不同义在状态机层面正常，由 `*_status` 列名前缀区分（`instance_status` / `task_status` / `step_status` / `node_status`）。

### 2.3 `WAITING` 仅出现在 job_instance / job_step_instance

**语义**：等待外部资源（如等上游文件到达、等审批）。其他状态机走 CREATED → READY 直接转移（无外部等待）。

**结论**：**保持差异**。

### 2.4 `TERMINATED` 全表统一含义

**语义**：人工干预强制终止（运维点 "强制终止"），区别于业务失败 FAILED。状态机进入 TERMINATED 后不再转移，且不会触发自动重试。

**结论**：✓ 设计一致。

## 3. 主链路转移图

### 3.1 job_instance 状态机

```
CREATED → WAITING → READY → RUNNING → SUCCESS
   │                  │        │          │
   │                  │        ├── PARTIAL_FAILED （部分 partition 失败）
   │                  │        │
   │                  │        └── FAILED
   │                  │
   │                  ├── CANCELLED （schedule 被取消）
   │                  │
   ↓                  ↓
TERMINATED      TERMINATED
```

### 3.2 outbox_event 状态机

```
NEW → PUBLISHING → PUBLISHED
  ↑        │           
  │        ├── FAILED → ... (退避后回到 NEW 重试) → ...
  │        │
  └────────┴── GIVE_UP （retry_count 耗尽）
```

### 3.3 trigger_request 状态机（ADR-010）

```
PENDING → ACCEPTED → LAUNCHED
   │         │           ↑
   │         │           │ TriggerLaunchConsumer 消费成功后回写
   │         │           │
   │         └─→ FAILED ─┘
   │              │
   ↓              ↓
   └─→ GIVE_UP ←─┘ （ad-hoc reconciler 回退）
```

## 4. 状态推进的硬约束

来自 CLAUDE.md §架构硬约束：

- **Orchestrator 是唯一状态主机**；Worker 不能直接改写 `job_instance` / `workflow_run` / `workflow_node_run`
- Worker 通过 HTTP `report` 上报（含 i18n 三元组 + ADR-009 节点 outputs），orchestrator 推进状态机
- console-api 不能直接 UPDATE/DELETE outbox_event，运维操作必须经 orchestrator `/internal/outbox/*` 接口
- `outbox_event` 必须与状态变更同事务（保证业务/事件一致性）
- Worker 执行前必须先 CLAIM（job_task READY → RUNNING 由 worker 通过 CLAIM 触发，不能绕过）

## 5. 实现层关键 enum 文件

```
batch-common/src/main/java/io/github/pinpols/batch/common/enums/
  ├─ JobInstanceStatus.java
  ├─ PipelineRunStatus.java
  ├─ WorkflowRunStatus.java
  ├─ WorkflowNodeRunStatus.java
  ├─ TaskStatus.java
  ├─ PartitionStatus.java
  ├─ StepInstanceStatus.java         # 注：类名无 "Job" 前缀
  ├─ OutboxPublishStatus.java
  ├─ CompensationCommandStatus.java
  └─ WorkerRegistryStatus.java
  # trigger_request.request_status 无 Java enum class，仅靠 V60 DB CHECK 约束回退
```

所有 enum 实现 `DictEnum` 接口（CLAUDE.md §领域数据字典），提供 `code()` / `label()`，统一通过 `DictEnum.fromCode()` 反查。

## 6. 守护测试

- `ConsoleMetaEnumRegistrationTest` 强制所有面向前端的 enum 必须登记到 `ConsoleMetaQueryService.REGISTRATIONS`
- `*StatusTest`（每个 enum 一个测试）覆盖 fromCode / labels / codes 的反查行为
- 新增 enum 必须同步两层守护，否则 CI 拦截

## 7. 未来工作

- 考虑提取 `StateMachineEngine<T extends DictEnum>` 通用框架，集中校验"非法状态转移"（目前由各 service 散落 if-else 校验）
- `pipeline_instance.COMPENSATING` 与 `compensation_command` 的关系建议增强文档（目前两者更新不在一个事务内，靠最终一致性回退）
