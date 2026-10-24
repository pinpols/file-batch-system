# pipeline_* 与 workflow_* 职责边界

> **现状与目标区分（2026-10-14）**：Pipeline 执行/观测模型保留；新执行按 Job 类型解析系统 Pipeline。Job 定义与 Workflow 定义仍为两个联动对象。Workflow 是所有顶层运行的编排入口，单作业 Workflow 关联一个 Job。单作业 Workflow 的 schedule source 已切换到 Workflow；组合 Workflow 的根 Launch 与剩余旧配置迁移尚未完成。细节以[实施方案](../plans/workflow-unified-execution-and-system-pipeline-plan-2026-10-14.md)为准。

> **结论先行**：Workflow 管理 Job 之间的编排；Pipeline 是文件类 Job 按类型执行的固定阶段链。二者都保留，但新配置中不再由 Workflow 节点引用租户可编辑的 Pipeline 定义；Pipeline 也不是独立顶层调度入口。

---

## 1. 一图分清

```mermaid
flowchart TB
  subgraph Workflow["workflow_* — 跨 job 的 DAG 编排"]
    direction LR
    W1[workflow_definition] --- WN1[workflow_node<br/>WorkflowNodeType]
    WN1 -- related_job_code --> J1[job_definition]
    WN1 --- WE[workflow_edge<br/>SUCCESS / FAILURE / CONDITION]
  end

  subgraph Pipeline["pipeline_* — 文件域内的步骤链路"]
    direction LR
    J1 -- JobType --> SYS[SystemPipelineCatalog<br/>平台代码固定定义]
    SYS --- PSD[固定阶段序列<br/>IMPORT / EXPORT / PROCESS / DISPATCH]
    PI[pipeline_instance] --- PSR[pipeline_step_run]
  end

  J1 -. 运行时按类型实例化 .-> PI
```

**关系一句话**：Workflow 节点引用 Job；文件类 Job 的类型决定系统 Pipeline。`pipeline_definition`、`pipeline_step_definition` 仍保留历史与兼容用途，但不再是新 Job 的可编辑关联来源。

---

## 2. 各自管什么

| 维度 | `workflow_*`（DAG 编排） | `pipeline_*`（文件链路）|
|---|---|---|
| 粒度 | **顶层执行的 Job 组合与控制流** | **单个文件类 Job 内部的固定阶段** |
| 主要场景 | 单 Job、串行/并行 Job、分支、汇合、依赖和整体调度 | IMPORT / EXPORT / PROCESS / DISPATCH 阶段推进 |
| 定义与运行事实 | `workflow_definition` / `workflow_node` / `workflow_edge` / `workflow_run` / `workflow_node_run` | 新执行身份来自 `SystemPipelineCatalog`；`pipeline_definition` / `pipeline_step_definition` 表保留兼容/历史，运行表为 `pipeline_instance` / `pipeline_step_run` |
| 主消费者 | `batch-orchestrator`（Workflow 推进、节点 Job 派发、join 与整体收敛） | `batch-worker-import` / `batch-worker-export` / `batch-worker-process` / `batch-worker-dispatch` |
| 节点／步骤类型 | <!-- enum-sync:WorkflowNodeType:start -->`TASK`, `GATEWAY`, `FILE_STEP`, `START`, `END`, `JOB`, `WAIT`<!-- enum-sync:WorkflowNodeType:end -->（`FILE_STEP` 为 legacy 兼容；目标执行节点引用 Job）| 由系统目录与 Worker 阶段实现共同固定；用户不可编辑阶段顺序 |
| 边／条件 | <!-- enum-sync:WorkflowEdgeType:start -->`SUCCESS`, `FAILURE`, `CONDITION`, `ALWAYS`<!-- enum-sync:WorkflowEdgeType:end --> 边 + GATEWAY join（<!-- enum-sync:WorkflowJoinMode:start -->`ALL`, `ANY`, `N_OF`<!-- enum-sync:WorkflowJoinMode:end -->） | 固定顺序，无用户配置的分支；阶段内恢复语义归 Worker |
| 状态机 | `workflow_run.run_status`：<!-- enum-sync:WorkflowRunStatus:start -->`CREATED`, `RUNNING`, `PAUSED`, `SUCCESS`, `FAILED`, `TERMINATED`, `SUCCESS_DRY_RUN`, `FAILED_DRY_RUN`<!-- enum-sync:WorkflowRunStatus:end --> | `pipeline_instance.run_status`：<!-- enum-sync:PipelineRunStatus:start -->`CREATED`, `RUNNING`, `SUCCESS`, `FAILED`, `COMPENSATING`, `TERMINATED`<!-- enum-sync:PipelineRunStatus:end --> |
| 入口文档 | [workflow-dependency-guide.md](workflow-dependency-guide.md) + [ADR-009 workflow-param-dsl](adr/ADR-009-workflow-param-dsl.md) | [docs/design/file-pipeline-design.md §9](../design/file-pipeline-design.md) |

---

## 3. 怎么选

判断顺序：

1. **任何顶层运行，包括只运行一个 Job** → `workflow_*`；单 Job 使用受管 `START → JOB → END`。
2. **需要配置 Job 参数、模板、通道或分片** → `job_definition`。
3. **文件类 Job 需要固定处理阶段** → Job 的类型映射到系统 Pipeline；不创建、选择或编辑租户 Pipeline 定义。
4. **需要组合多个执行单元** → Workflow 节点引用已有 Job；不要用 FILE_STEP 直接引用 Pipeline。

**反例**：

- ❌ "我要调整某个 Job 的 Pipeline 阶段顺序" → 阶段顺序是系统执行契约，不能在租户配置中编辑
- ❌ "我想让一个单 Job 绕过 Workflow 直接运行" → 调用该 Job 的受管单作业 Workflow
- ❌ "我有两个 Job 想串起来" → 不在 Pipeline 中塞 Job，使用 Workflow 节点和边

---

## 4. 为什么命名相似但不重叠

Pipeline 先解决文件处理阶段观测与恢复；Workflow 后用于组合执行单元。统一运行入口不会删除 Pipeline：Pipeline 仍负责 Job 内部阶段事实，Workflow 负责顶层状态与编排。旧 FILE_STEP/tenant Pipeline 引用在迁移期仅保留兼容和历史解释能力。

---

## 5. 跨表 join 速查

| 想看 | join 路径 |
|---|---|
| 某 Workflow 节点的 Job 使用哪类系统 Pipeline | `workflow_node.related_job_code` → `job_definition.job_type` → `SystemPipelineCatalog` |
| legacy Workflow 的直接 Pipeline 引用 | `workflow_node.related_pipeline_code` → 旧 `pipeline_definition`；仅用于转换/历史解释，不能作为新模型入口 |
| 某 pipeline_instance 是哪个 workflow_run 触发的 | 优先通过关联的 `job_instance` 与 Workflow 节点运行关系查询；旧数据可按 `trace_id` 反查，但 trace 不是新的关联键 |
| 某 pipeline_instance 关联的 job | `pipeline_instance.related_job_instance_id` → `job_instance.id` |
| 某 job 同时被几个 workflow 引用 | `workflow_node` where `related_job_code = ?` |

---

## 6. 相关文档

- [workflow-dependency-guide.md](workflow-dependency-guide.md) — workflow DAG 主文档
- [docs/design/file-pipeline-design.md §9](../design/file-pipeline-design.md) — 文件链路设计（pipeline 主文档）
- [ADR-009 workflow-param-dsl](adr/ADR-009-workflow-param-dsl.md) — workflow 节点参数 DSL
- [docs/agent-baseline.md §领域数据字典](../../docs/agent-baseline.md) — `WorkflowNodeType` / `WorkflowEdgeType` / `WorkflowType` / `JobType` 枚举
