# Pipeline vs Workflow vs Job 边界

> **当前开发分支口径（2026-10-24）**：单作业和组合图均由 WorkflowRun 承载顶层运行，JOB 节点按需创建独立 JobInstance；按 Job 类型解析系统固定 Pipeline。旧顶层 Job 包装、租户 Pipeline 编辑和旧配置包格式不作为新部署入口。ADR-048 采用全新数据库与隔离命名空间切换，不承诺旧载荷/旧运行原地兼容；部署验收另看当前矩阵。

> **status**: 固化（2026-05-03）  
> **scope**: 三套编排体系的职责切分、选型决策、跨域引用规则

## 1. 三套体系总览

| 体系 | 定义表 | 运行表 | 适用场景 | 用户可定制 |
|---|---|---|---|---|
| **Pipeline** | `SystemPipelineCatalog` 固化阶段/版本，Job 保存业务参数；旧租户定义表及关联ID已在 V347 退役 | `pipeline_instance` `pipeline_step_run` | 文件类 Job 按类型执行的内部阶段链 | ❌ 阶段和顺序固定 |
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
- 保留 `pipeline_instance` / `pipeline_step_run` 运行历史；空库部署通过 V347 退役旧租户定义表，不提供旧库数据迁移兼容

### 2.3 不允许做的事

- ❌ 用户自定义新 stage（要新阶段去走 workflow）
- ❌ 加 GATEWAY 分支（pipeline 是线性流水，分支走 workflow）
- ❌ 在 pipeline 内部跨 Job（pipeline 等于一个 Job 内部）

## 3. Workflow —— 用户 DAG 编排

### 3.1 节点类型（新部署图）

- `START` / `END`：持久化流程边界，由 Orchestrator 推进，不派 Worker 任务。
- `JOB`：通过 `related_job_code` 引用同租户 Job；节点到达后才创建该节点的 JobInstance。
- `GATEWAY`：条件分支与汇聚。
- `WAIT`：持久化 Sensor 等待，按超时策略失败或跳过下游，见 ADR-028。
- `TASK` 枚举/辅助实现仅用于识别历史模型；新根运行没有父 Job，不能直接执行 TASK。`FILE_STEP` 与 `related_pipeline_code` 已退出新图契约。

Workflow 是平面 DAG，不支持嵌套 Workflow，也没有独立的审批节点类型。高风险操作审批属于命令/操作域。

### 3.2 边类型（`workflow_edge.edge_type`）

- `SUCCESS`：上游成功才触发下游
- `FAILURE`：上游失败才触发下游（补偿分支）
- `CONDITION`：按 condition_expr 判断（参数 DSL 见 ADR-009）
- `ALWAYS`：无条件触发（START/END 边常用）

### 3.3 设计意图

Workflow = "**用户表达业务编排意图**"：
- 前端配置（拖拽 / Excel 导入 / API 提交）
- 支持受权限与审批约束的暂停、重放等运维操作
- 节点参数 DSL（`$.nodes.X.output.Y`）让上游产出可被下游引用（ADR-009）

### 3.4 与 Pipeline 的关系

新模型中 Workflow 执行节点统一引用 Job，Job 类型再决定系统 Pipeline；新节点不直接绑定 Pipeline 定义。全新数据库不保留 `FILE_STEP.related_pipeline_code` 字段，Workflow Excel、配置包与 API 只接受 Job 引用。Workflow Run 是整体运行事实，Pipeline Instance/Step Run 保留为执行明细。

## 4. Job —— 可复用执行配置

### 4.1 类型（`job_definition.job_type`）

- `GENERAL` —— 通用 Java handler，handlerClass 跑业务代码
- `IMPORT` / `EXPORT` / `DISPATCH` —— 触发对应类型 Pipeline 执行
- `PROCESS` —— 触发 PROCESS Pipeline（WAP 模式）
- `ATOMIC` —— 专用原子任务执行器
- `BUNDLE_IMPORT` / `BUNDLE_EXPORT` / `BUNDLE_DISPATCH` —— manifest 展开对应文件类型分区；`WORKFLOW` 已退出当前 JobType

### 4.2 设计意图

Job = "**Workflow 引用的可复用执行配置**"：
- Trigger 顶层目标是 Workflow 定义，单 Job 从其受管 Workflow 启动；不能把 Job code 当顶层请求目标
- Job 是审批 / 配额 / 重试 / 补偿的语义边界
- job_definition.job_type 决定运行时分发策略（worker_group / topic / handler）

### 4.3 执行层级（不含子 Workflow）

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

### 6.3 节点仅引用 Job

`workflow_node.related_job_code → job_definition.job_code` 在同租户范围校验。Job 类型映射系统 Pipeline，Pipeline 不反向编排 Workflow。原 `FILE_STEP → pipeline_definition` 仅属于历史设计，不能据此配置新图。

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
