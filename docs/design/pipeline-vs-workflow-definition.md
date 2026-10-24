# Pipeline vs Workflow vs Job 边界

> **2026-10-14 目标与现状**：Pipeline 模型、定义表、步骤表、实例和阶段历史均保留；退役的是新 Job 对租户可编辑 Pipeline 定义的手工绑定，新执行按 Job 类型选择系统固定 Pipeline。Job 定义和 Workflow 定义仍是两个联动对象；单 Job 也经受管 `START → JOB → END` Workflow 运行。当前已实现系统 Pipeline 身份、Job/Workflow 关系和单作业调度字段迁移；组合 Workflow 根 Launch、旧配置包转换、旧 CRUD 写入退出仍未完成。以[实施方案](../plans/workflow-unified-execution-and-system-pipeline-plan-2026-10-14.md)为准。

> **status**: 固化（2026-05-03）  
> **scope**: 三套编排体系的职责切分、选型决策、跨域引用规则

## 1. 三套体系总览

| 体系 | 定义表 | 运行表 | 适用场景 | 用户可定制 |
|---|---|---|---|---|
| **Pipeline** | 新执行由 `SystemPipelineCatalog` 固化；`pipeline_definition` / `pipeline_step_definition` 表保留兼容和历史 | `pipeline_instance` `pipeline_step_run` | 文件类 Job 按类型执行的内部阶段链 | ❌ 阶段和顺序固定 |
| **Workflow** | `workflow_definition` `workflow_node` `workflow_edge` | `workflow_run` `workflow_node_run` | 所有顶层运行入口；单 Job、多 Job、分支/并行 | ✅ 组合图与调度策略 |
| **Job** | `job_definition` | `job_instance` `job_partition` `job_task` | 可复用执行单元，被 Workflow 节点引用 | ✅ 类型化业务参数 |

## 2. Pipeline —— 文件处理专用

### 2.1 系统固定阶段（按 Job 类型映射）

- **IMPORT**：RECEIVE → PREPROCESS → PARSE → VALIDATE → LOAD → FEEDBACK
- **EXPORT**：PREPARE → GENERATE → STORE → REGISTER → COMPLETE
- **DISPATCH**：PREPARE → DISPATCH → ACK → RETRY → COMPENSATE → COMPLETE
- **PROCESS**：PREPARE → COMPUTE → VALIDATE → COMMIT → FEEDBACK（WAP 模式）

### 2.2 设计意图

Pipeline = "**文件处理黄金路径**"，按 Job 类型选择固定阶段：
- 不允许用户拖拽节点改顺序（保持流水线可观测、可重试、可补偿语义一致）
- step 失败的重试 / 补偿 / 跳过策略全在 worker 内部实现
- 新运行身份记录系统 Pipeline code/version；运维可继续通过 `pipeline_step_run` 看阶段进度
- 既有 Pipeline 定义和运行历史保留，不因配置入口退役而清表

### 2.3 不允许做的事

- ❌ 用户自定义新 stage（要新阶段去走 workflow）
- ❌ 加 GATEWAY 分支（pipeline 是线性流水，分支走 workflow）
- ❌ 在 pipeline 内部跨 Job（pipeline 等于一个 Job 内部）

## 3. Workflow —— 用户 DAG 编排

### 3.1 节点类型（`workflow_node.node_type`）

- `START` / `END`：流程边界
- `JOB`：执行一个已有 Job（通过 `related_job_code` 绑定）；目标统一执行语义
- `TASK`：历史上在 Workflow 内生成任务/分区的路径；与 `JOB` 目前并非同义，迁移须核对资源、计数和恢复语义
- `FILE_STEP`：旧节点直接绑定 Pipeline 定义；迁移目标是解析为同租户 Job 引用并校验 Job 类型，不能继续新建该绑定
- `GATEWAY`：分支 / 汇聚（条件 / ANY join / ALL join）

### 3.2 边类型（`workflow_edge.edge_type`）

- `SUCCESS`：上游成功才触发下游
- `FAILURE`：上游失败才触发下游（补偿分支）
- `CONDITION`：按 condition_expr 判断（参数 DSL 见 ADR-009）
- `ALWAYS`：无条件触发（START/END 边常用）

### 3.3 设计意图

Workflow = "**用户表达业务编排意图**"：
- 前端配置（拖拽 / Excel 导入 / API 提交）
- 支持人工干预（审批节点 / 暂停重跑 / 局部补偿）
- 节点参数 DSL（`$.nodes.X.output.Y`）让上游产出可被下游引用（ADR-009）

### 3.4 与 Pipeline 的关系

新模型中 Workflow 执行节点统一引用 Job，Job 类型再决定系统 Pipeline；新节点不直接绑定 Pipeline 定义。旧 `FILE_STEP.related_pipeline_code` 仅在兼容期用于历史查询/转换预览，配置包与 API 的写入口尚待收敛。Workflow Run 是整体运行事实，Pipeline Instance/Step Run 保留为执行明细。

## 4. Job —— 最小执行单元

### 4.1 类型（`job_definition.job_type`）

- `GENERAL` —— 通用 Java handler，handlerClass 跑业务代码
- `IMPORT` / `EXPORT` / `DISPATCH` —— 触发对应类型 Pipeline 执行
- `PROCESS` —— 触发 PROCESS Pipeline（WAP 模式）
- `WORKFLOW` —— 触发一条 Workflow 执行

### 4.2 设计意图

Job = "**调度的最小可声明单元**"：
- trigger 只能 fire 一个 Job（不能直接 fire 一个 Pipeline 或 Workflow node）
- Job 是审批 / 配额 / 重试 / 补偿的语义边界
- job_definition.job_type 决定运行时分发策略（worker_group / topic / handler）

### 4.3 嵌套关系

```
Trigger / API
  ↓
Workflow Run (workflow_definition)
  ├─ JOB node (related_job_code=IMPORT_JOB)
  │   ↓ → Job Instance / partitions / tasks
  │       ↓ JobType.IMPORT
  │       System Pipeline IMPORT (fixed stages)
  └─ JOB node (related_job_code=EXPORT_JOB)
      ↓ → Job Instance / partitions / tasks → System Pipeline EXPORT
```

## 5. 选型决策树

```
新增执行场景
  ↓
定义或复用 Job（类型决定执行器与系统 Pipeline）
  ↓
通过 Workflow 表达任何顶层运行：单 Job 默认流程或组合 DAG
  ↓
运行记录以 Workflow Run 为整体，保留 Job/Partition/Task/Pipeline 阶段明细
```

## 6. 业务代码红线

### 6.1 禁止跨表 UNION 查询

```sql
-- ❌ 禁止
SELECT instance_no, status, started_at FROM batch.pipeline_instance WHERE tenant_id = ?
UNION ALL
SELECT instance_no, status, started_at FROM batch.workflow_run WHERE tenant_id = ?

-- ✓ 改为：分别查询，应用层合并
```

理由：pipeline_instance 是 worker 内部记录（运维不介入），workflow_run 是用户编排运行态（支持人工干预）。两者状态字段、生命周期、操作权限都不同，UNION 会让前端展示混乱。

### 6.2 禁止从外部直接更新 pipeline_step_run

`pipeline_step_run` 由 worker 在执行 stage 时写入，外部代码（console-api / orchestrator API）禁止 UPDATE / DELETE。状态推进由 worker `report` 上报触发。

### 6.3 workflow_node.related_pipeline_code 单向引用

- ✓ workflow_node 节点 → pipeline_definition.job_code（FILE_STEP 节点）
- ❌ pipeline_definition → workflow_node（pipeline 不知道自己被哪条 workflow 引用）

理由：pipeline 是被复用的"工作单元"，被多条 workflow 引用是正常的；反向引用会造成强耦合。

## 7. 历史与未来

### 7.1 为什么会有 pipeline / workflow 双轨

V4/V5（早期）只有 workflow_definition + workflow_node。V6 加 pipeline_definition + pipeline_step_run，原因：

- 早期 workflow 节点支持任意 handler，但 IMPORT 等场景的 9 阶段总是手写一套，工程上抽象成"内置 pipeline"简化处理
- pipeline_step_run 比 workflow_node_run 维度更细（精确到 stage），观测性更好

### 7.2 是否考虑合并两套体系

讨论过几次，结论是**不合并**：
- pipeline 已经稳定，强行合并到 workflow 会让 workflow_node_run 表加大量 nullable 字段，得不偿失
- 用户心智模型上"文件处理流水线" vs "DAG 编排"是两个不同概念，强行抹平反而增加学习成本

### 7.3 长期演进

- pipeline_step_run 考虑用 PG declarative partition 按 biz_date 分区（高频写表）
- workflow 节点参数 DSL 计划增强（限制 ADR-009 的子集，避免任意 JSONPath）
