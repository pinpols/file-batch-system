# 架构适配性评估文档复核

日期：2026-11-04。对象：[批量调度系统架构适配性评估](architecture-suitability-assessment-2026-11-02.md)（下称“评估”）。基线：后端 `main`，HEAD `e5d2d140e`（已含 #1307–#1311）。方法：`project-engineering-review`（架构评估复核）；只读核对文档中的事实陈述、链接、引用代码与后续追踪，未修改评估原文、产品代码或配置，未运行测试或访问任何环境。

## 结论

评估的总体判断（WorkflowRun 统一模型、DB 事实源 + 事务 Outbox + claim/report + 专用 Worker 方向合理，但不能由设计完整推断生产成熟）与当前代码一致，所列边界和“未覆盖”声明克制、可追溯：26 个链接与锚点全部可解析，7 个引用类均存在，抽查的配置与行为陈述成立。

主要问题不在结论本身，而在**建议的后续闭环**：评估提出的 5 项优化候选和 1 个口径裁定，只有“按状态所有权收敛控制面”被 [2026-11-03 架构设计审查](architecture-design-review-2026-11-03.md) 承接并部分实施；其余没有进入 `todo-master`，也没有负责人或裁定入口。评估第 5 条点名的代码注释漂移至今未修。

## 发现（按影响排序）

### R1 / 治理缺口 / 有当前证据：优化候选与口径裁定未进入当前待办

- **证据**：评估“可考虑的架构优化顺序”1、2、4、5（结果未知恢复合同、积压容量与接纳语义、保留期与恢复窗口联动、以现有部署与观测降低运维复杂度），以及“架构图优化记录”中 `JobTask FAILED → READY` 复位与“终态不回非终态”原则的口径差异，在 `docs/plans/todo-master.md` 中均无对应条目；`todo-master` 也未链接评估。口径差异仅记录在 [`core-model.md`](../architecture/core-model.md) 的“待核对的终态边界”说明中。当前代码仍有该路径：`JobTaskMapper.xml` 的 `resetForRetry` 允许 `task_status in ('READY','RUNNING','FAILED')` 复位。
- **影响**：按[文档治理规范](../standards/document-governance.md)，当前待办只进入 `todo-master`；建议停留在带日期的评估里，后续轮次无法判断是否已排期、已否决或已失效。终态口径不裁定，状态图、Mapper 状态集合测试和新代码会各自按不同理解演进。
- **建议**：在 `todo-master` 的“状态所有权与模块边界”同组或新增“架构适配性候选”行，逐项标注“待排期 / 依赖业务 SLO 裁定 / 已由某文档承接”；`FAILED → READY` 单列为需维护者裁定的口径事项（保留为受限重试并在模型中登记例外，或收敛实现）。
- **关闭条件**：每个候选在 `todo-master` 有状态与入口；终态口径有裁定记录，并同步 `core-model.md` 与相关 Mapper 状态测试。

### R2 / 文档漂移 / 有当前证据：评估第 5 条点名的派发服务注释仍未修正，且已自相矛盾

- **证据**：`DefaultWorkflowNodeDispatchService` 类注释写“按节点类型分三条路径派发”，紧接着列出“四种派发模式”，并把 TASK 描述为“普通节点”；`dispatchNode` 方法注释仍是“gateway / JOB / task 三条路径之一”。实际代码按 END、GATEWAY/START、JOB、WAIT 路由；TASK 只作为遗留路径保留，缺少父 JobInstance 时抛 `error.workflow.legacy_task_node_requires_job_migration`；另外，带 `relatedJobInstanceId` 的旧根运行在路由前就对所有节点类型抛 `error.workflow.root_job_launch_retired`。
- **影响**：新读者会把 TASK 当作当前一等节点类型来扩展；评估已判断其“未证明运行缺陷”，本复核同意，影响限于可理解性。
- **建议**：改为按实际路由列出 END / GATEWAY·START / JOB / WAIT，并把 TASK 标为仅供尚未迁移定义的遗留路径，同时把旧根运行拒绝写成所有路径的前置条件。属于纯注释修改。
- **关闭条件**：类注释与方法注释同代码分支一致；无行为变化。

### R3 / 可追溯性 / 建议：评估未指向已承接其建议的后续文档

- **证据**：评估优化候选第 3 条由 2026-11-03 审查承接，并已实施 ARCH-02/03、CONV-01..04（#1307、#1308）和 ARCH-01 的 Worker 范围（#1309）；评估“租户”行的“未在目标环境验证数据库角色”也由 #1309 部分推进（仓库内已有 Worker 角色与授权验证，目标环境仍未切换）。评估正文没有指向这些后续文档或 PR。
- **建议**：不改评估原有结论，只在文末追加一行带日期的“后续承接”说明，链接 11-03 审查与本复核；状态以 `todo-master` 为准。
- **关闭条件**：读者从评估能定位到承接文档。

### R4 / 文档结构 / 建议：一份文件混用四个基线，文首只声明了第一个

- **证据**：文首声明基线为 `codex/closure-review` `fb7ec82`；“用户提供的架构概览核对”使用 `d60868a`，“架构图覆盖与改进评估”和“架构图优化记录”使用另一分支 `codex/fix-atomic-redelivery-e2e` 的 `e25e2eeb`。各节虽有自述，但文首和“覆盖与证据”表让人以为全文同一基线。
- **建议**：文首补一句“各节基线不同，以节内声明为准”，或拆为同日多份记录。纯说明性调整。

## 已核对成立的陈述（本基线）

| 评估陈述 | 核对结果 |
|---|---|
| 默认 OTLP 导出关闭 | 成立：`batch-defaults.yml` 中 `MANAGEMENT_OPENTELEMETRY_ENABLED` 默认 `false` |
| Relay 存在 GIVE_UP 边界，Outbox 不等于端到端不丢 | 成立：`TriggerOutboxRelay` 与 `TriggerOutboxRelayProperties` 的最大尝试次数到达后标 GIVE_UP |
| 控制节点不创建空 Worker 任务 | 成立：GATEWAY/START 同事务合成完成的 node_run，WAIT 只建持久等待，均不建任务 |
| 新根路径拒绝旧 TASK 派发 | 成立：旧根运行在路由前统一拒绝，遗留 TASK 另需父 JobInstance，见 R2 |
| 模块图 common→Console 方向已修正 | 成立：`project-structure.md` 当前模块图中已无该边 |
| `system-flow-overview` 的 Outbox 绝对化表述已校准 | 成立：已说明 GIVE_UP、不可恢复拒绝及告警与恢复条件 |
| WAITING 无统一硬上限、派发无显式 lag gate 已登记为边界 | 成立：见 `heavy-workload-guarantees.md` §7 |
| 引用的类、ADR 与手册 | 26 个链接（含锚点）及 7 个类均存在 |

## 未覆盖

- 未复核评估中的规模估算（人日）和 Mermaid 统计数量；它们是当时基线下的评估口径，不作为当前事实。
- 未运行测试、渲染全部图或访问目标环境；评估“未覆盖”列中的运行时与容量结论仍未验证。
- 只复核这一份评估；其他架构文档的漂移未逐份扫描。

## 整改记录（2026-11-04）

- R1：`todo-master` 新增“架构适配性候选”行，登记 ASSESS-20261102-01..04 与待裁定事项 ASSESS-20261102-DEC-01（`FAILED → READY` 口径）；候选 3 标注由 11-03 审查承接。
- R2：`DefaultWorkflowNodeDispatchService` 类注释与 `dispatchNode` 注释改为按 END / GATEWAY·START / JOB / WAIT / 遗留 TASK 路由，并将旧根运行拒绝写为前置条件；仅修改注释，无行为变化。
- R3、R4：评估文首补充“各节基线以节内声明为准”，文末追加“后续承接”说明；评估原有结论未改。
- 验证：只做基础校验（链接解析、`git diff --check`、Spotless、Orchestrator 编译）；完整门禁交给 CI。
- R1 后续：ASSESS-20261102-DEC-01 已裁定保留为登记的受限复位例外，见 [`core-model.md`](../architecture/core-model.md)；新增 `BoundedRetryResetCallerArchTest` 与 `BoundedRetryResetSqlContractTest` 守护入口与源状态。
