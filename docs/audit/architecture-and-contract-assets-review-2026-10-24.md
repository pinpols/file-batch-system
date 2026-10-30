# Workflow 分支架构与契约资产核查（2026-10-24）

## 1. 基线、范围与边界

- 工作树：`~/.codex/worktrees/workflow-daily-integration/file-batch-system`；分支 `feature/workflow-daily-integration`，核查起点 HEAD `ee01e9751`。
- 与 main 已提交基线相同；实际改造包含大量暂存、未暂存及未跟踪内容，不能以提交差异为空判断没有变化。本次按工作树实际代码与五类文档核对，保留其它会话改动。
- 范围：architecture 72、design 41、api 39、sdk 12、dict 4，共 **168 个文件**，其中 **133 份 Markdown + 35 份契约/黄金样本/辅助资产**。逐文件清单见 §5。
- 方法：逐文件检查主题、当前/历史定位、引用与本次变更影响；对命中的模型、图、端点、字段、日期和恢复说明回查 DTO、Controller、Mapper、枚举、迁移及配对前端。未命中直接冲突的文档保留，不为增加更新数量改写。
- 这是文档适配核查，不是全仓代码安全审计、数据库现场 catalog 验收或新一轮运行验证。长期资产原位更新；历史 ADR 原决策和批次证据保留，新增适用性说明不改写决策历史。

## 2. 五类目录的固定职责

| 目录 | 内容 | 更新方式 |
|---|---|---|
| `architecture/` | 架构、模型、原语、边界、运行主链与 ADR | 当前事实原位更新；ADR 追加适用性/实现状态 |
| `design/` | 业务与能力设计、数据库逻辑模型 | 原设计更新；早期逻辑 DDL 不作部署脚本 |
| `api/` | OpenAPI、协议与 API 黄金样本 | 从实际 Controller/DTO 核对；样本按协议行为维护 |
| `sdk/` | SDK 接入、通信协议与发布契约 | 区分平台协议、各语言实现与验证证据 |
| `dict/` | 错误码与配置键生成参考、统一术语 | 生成物从权威源刷新，不手工造第二份值表 |

单次核查结果留在本报告，实施进度仍见[总方案](../plans/workflow-daily-integration-plan-2026-10-14.md)、[验收矩阵](../plans/workflow-daily-integration-acceptance-matrix-2026-10-14.md)和[执行日志](../plans/workflow-daily-integration-execution-log-2026-10-14.md)，不在资产文档复制一套时效性统计。

## 3. 本次修正与源码依据

| 主题 | 当前事实与修正 | 主要依据 |
|---|---|---|
| 根运行与原语 | WorkflowRun 是根；JOB 按需物化 JobInstance；控制节点不派空任务；平面 DAG，不嵌套 Workflow | `DefaultWorkflowRunLaunchService`、`DefaultWorkflowNodeDispatchService`、`WorkflowNodeTerminalFinalizer`；[核心模型](../architecture/core-model.md) |
| 图与主链 | 更新总流程、依赖时序、模块通信、数据库关系图；旧虚拟父任务、FILE_STEP、顶层 WORKFLOW Job 不作为新入口 | [总流程](../architecture/system-flow-overview.md)、[依赖指南](../architecture/workflow-dependency-guide.md)、ADR-048 |
| Pipeline | 系统目录固定类型/版本/阶段；业务参数可配置，不能覆盖阶段控制流；运行阶段观测保留 | `SystemPipelineCatalog`、`SystemPipelineStepConfiguration`；[边界](../design/pipeline-vs-workflow-definition.md) |
| 业务日与义务 | 开日登记 BatchDay 与预期，不提前造全部作业；日期/区间冻结随重试恢复继承 | `BatchDayOpenScheduler`、`ExpectedWorkRegistrationService`、根 launch；[日期语义](../design/time-date-semantics.md) |
| 分区唯一性与表组 | V182 全局去重台账+同事务认领；新增机会/覆盖/预期/Replay/回执职责；V318 所有者复合 FK | Flyway、`OutboxEventMapper.xml`、`JobInstanceMapper.xml`；[数据库指南](../design/database-schema-guide.md)、[分区幂等](../design/partition-idempotency-decision.md) |
| 配置包 | 完整导出10数据sheet，排除两张旧 Pipeline sheet，辅助表另计；场景模板可裁剪 | `ConfigPackageExcelWorkbookWriter.exportSheetDefs`、`ConfigPackageSheetSpecs`；[配置包设计](../design/tenant-config-package-excel-9plus2-design.md) |
| 通知与审计 | Job/Workflow终态分开；首次冻结的回执与投递按事件族归档；配置同步目标回执另有职责 | V328/V330/V345/V346；[通知边界](../architecture/notification-and-audit-boundary.md) |
| 内部协议 | 补录已有批量claim/report、非幂等开始标记，补齐EffectiveTaskConfig、Replay声明/围栏与整图预览凭证 | `TaskController`、`EffectiveTaskConfig`、`WorkerHeartbeatDto`、`WorkflowRunReplayPreviewResponse`；[OpenAPI](../api/orchestrator-internal.openapi.yaml) |
| SDK入口与路由 | 删除不存在的直接派task示例；使用真实Workflow trigger/状态核对；topic顺序、定向后缀和配置工厂按实际实现 | `ConsoleWorkflowDefinitionController`、`WorkflowTriggerRequest`、`BatchTopicResolver`、`KafkaOutboxPublisher`、Java SDK config/consumer |
| 设计器能力 | 画布当前4类节点；后端WAIT与画布支持分开；无APPROVAL节点；保存路径、锁、expectedVersion按实际接口 | 配对前端 `designer/types.ts`、`dagValidators.ts`、`workflowDesigner.ts`；[设计器](../design/workflow-dag-designer.md) |
| ADR | 保留原决定；更新新根、日期、幂等、依赖、WAIT、dry-run、SDK与Replay适用范围；撤销/替代不复活 | ADR-003/004/007/010/011/018/020/025/026/028/035/040/043/046/047/048/049及索引 |
| 字典 | 错误码源是YAML、Java和MD均为生成物；配置metadata不等于全部支持动态覆盖；术语跟随新根 | `gen-error-codes-dict.py`、`error-codes.yaml`、enum-sync；[dict索引](../dict/README.md) |

## 4. 保留的能力与验证边界

1. **SDK Replay 能力**：平台已定义 `replayCapabilities` 与 `replayExecutionGuard`；本次检查的五语言 SDK 源码未消费这些新字段。文档明确 UNKNOWN/失败关闭，不能将旧30份基础fixture或Java单侧验证解释成五语言Replay交付完成。若开放通用SDK重放，需声明上报、执行前匹配、非幂等开始证据与未知结果恢复的专项实现/验收，本次不擅自改代码。
2. **WAIT 与动态展开**：后端WAIT已有实现和Accepted ADR；当前画布没有WAIT类型。旧TASK fanOut辅助代码存在不代表新根图支持动态JOB mapping；新图使用Job分区或BUNDLE manifest，不额外造嵌套模型。
3. **运行监控**：Job实例告警保留且关联根/节点。未物化JOB的缺失义务需看occurrence/expected-work，不能据JobInstance空表推断空日成功；监控专用查询仍保留自己的业务策略口径，不因文档统一而擅改SQL。
4. **契约与验收**：内部OpenAPI是精选子集；当前漂移工作流仍为log-only路径存在性守护，不能宣传为字段级、方法级或真实HTTP全部守护。此报告只记录本次实际静态校验，不提升原验收等级。
5. **部署与历史**：ADR-048采用全新库和隔离消息空间；旧DDL、旧批次记录、旧SDK对齐行号均保留历史边界。目标环境、提供商、正式容量、CI与发布签收继续按原矩阵，不以本次文档变更销项。

## 5. 逐文件核查清单

“更新”表示本轮相对于任务开始快照发生文档变化；“保留”表示未发现与本次改造直接冲突，不构成该主题全部运行能力验证通过。API黄金样本逐个做JSON/schema校验；不为文档刷新改变样本行为值。

### architecture/

| 文件 | 主题/作用 | 本轮处理 |
|---|---|---|
| [README.md](../architecture/README.md) | 架构文档索引 | 更新：当前事实、图/字段或索引 |
| [ADR-001-dual-orm.md](../architecture/adr/ADR-001-dual-orm.md) | ADR-001: 全平台持久层统一 MyBatis + JdbcTemplate（不引入 JPA / Spring Data JDBC） | 保留：原决策与历史状态 |
| [ADR-002-transactional-outbox.md](../architecture/adr/ADR-002-transactional-outbox.md) | ADR-002: 使用事务性 Outbox 模式发布 Kafka 事件，不直接写 Kafka | 更新：当前事实/引用；保留历史证据 |
| [ADR-003-launch-t1-t2-split.md](../architecture/adr/ADR-003-launch-t1-t2-split.md) | ADR-003: launch() 的 T1/T2 事务拆分与 CGLIB 自注入 | 更新：当前事实/引用；保留历史证据 |
| [ADR-004-worker-lifecycle-template.md](../architecture/adr/ADR-004-worker-lifecycle-template.md) | ADR-004: Worker 生命周期使用模板方法模式（AbstractWorkerLoop） | 更新：当前事实/引用；保留历史证据 |
| [ADR-005-partition-count-resolver-chain.md](../architecture/adr/ADR-005-partition-count-resolver-chain.md) | ADR-005: 分区数量解析使用责任链模式（PartitionCountResolver） | 保留：原决策与历史状态 |
| [ADR-006-compensation-requires-new.md](../architecture/adr/ADR-006-compensation-requires-new.md) | ADR-006: 补偿/重试方法使用 REQUIRES_NEW 防止死锁级联 | 保留：原决策与历史状态 |
| [ADR-007-dual-datasource.md](../architecture/adr/ADR-007-dual-datasource.md) | ADR-007: 单 PostgreSQL 实例多 Schema 隔离（batch / quartz / archive） | 更新：当前事实/引用；保留历史证据 |
| [ADR-008-god-class-decomposition.md](../architecture/adr/ADR-008-god-class-decomposition.md) | ADR-008: God Class 分解为子服务 + Facade 模式 | 保留：原决策与历史状态 |
| [ADR-009-workflow-param-dsl.md](../architecture/adr/ADR-009-workflow-param-dsl.md) | ADR-009 · Workflow 节点间参数串联 DSL | 保留：原决策与历史状态 |
| [ADR-010-trigger-async-decoupling.md](../architecture/adr/ADR-010-trigger-async-decoupling.md) | ADR-010: Trigger → Orchestrator 异步解耦(trigger_outbox + Kafka) | 更新：当前事实/引用；保留历史证据 |
| [ADR-011-idempotency-boundary-alignment.md](../architecture/adr/ADR-011-idempotency-boundary-alignment.md) | ADR-011: Console / Trigger / Orchestrator 三层幂等责任边界对齐 | 更新：当前事实/引用；保留历史证据 |
| [ADR-012-failure-taxonomy.md](../architecture/adr/ADR-012-failure-taxonomy.md) | ADR-012 · 失败分类（Failure Taxonomy） | 保留：原决策与历史状态 |
| [ADR-013-distributed-tracing.md](../architecture/adr/ADR-013-distributed-tracing.md) | ADR-013 · 分布式 Tracing — Micrometer Observation + OpenTelemetry | 保留：原决策与历史状态 |
| [ADR-014-claim-idempotency.md](../architecture/adr/ADR-014-claim-idempotency.md) | ADR-014 · CLAIM 幂等保护 (invocation-id 模型) | 保留：原决策与历史状态 |
| [ADR-015-worker-side-outbox.md](../architecture/adr/ADR-015-worker-side-outbox.md) | ADR-015 · Worker 端本地 outbox — report 重投与防重复执行 | 更新：当前事实/引用；保留历史证据 |
| [ADR-016-batch-renew-lease-api.md](../architecture/adr/ADR-016-batch-renew-lease-api.md) | ADR-016 · Batch Renew Lease API — 减少 worker → orch HTTP 风暴 | 保留：原决策与历史状态 |
| [ADR-017-result-version-model.md](../architecture/adr/ADR-017-result-version-model.md) | ADR-017 · 结果版本（result_version）主模型 | 更新：当前事实/引用；保留历史证据 |
| [ADR-018-cross-batch-day-dag-dependency.md](../architecture/adr/ADR-018-cross-batch-day-dag-dependency.md) | ADR-018 · 跨批量日 DAG 依赖（pipe 模型） | 更新：当前事实/引用；保留历史证据 |
| [ADR-019-cross-domain-rate-limit.md](../architecture/adr/ADR-019-cross-domain-rate-limit.md) | ADR-019 · 跨业务域限流（business_domain 主模型） | 保留：原决策与历史状态 |
| [ADR-020-batch-day-replay.md](../architecture/adr/ADR-020-batch-day-replay.md) | ADR-020 · 批量日维度重放（batch_day_replay_session） | 更新：当前事实/引用；保留历史证据 |
| [ADR-021-data-quality-reconciliation.md](../architecture/adr/ADR-021-data-quality-reconciliation.md) | ADR-021 · 数据对账闭环（Data Quality / Reconciliation） | 保留：原决策与历史状态 |
| [ADR-022-forensic-audit-bundle.md](../architecture/adr/ADR-022-forensic-audit-bundle.md) | ADR-022 · Forensic 一键取证（Forensic Audit Bundle） | 保留：原决策与历史状态 |
| [ADR-023-multi-calendar-coordination.md](../architecture/adr/ADR-023-multi-calendar-coordination.md) | ADR-023 · 多日历联动 + 半天工作日 | 保留：原决策与历史状态 |
| [ADR-024-archive-tiering.md](../architecture/adr/ADR-024-archive-tiering.md) | ADR-024 · 冷热数据分层 + 长保留 | 更新：当前事实/引用；保留历史证据 |
| [ADR-025-workflow-static-validator.md](../architecture/adr/ADR-025-workflow-static-validator.md) | ADR-025 · Workflow 静态校验 | 更新：当前事实/引用；保留历史证据 |
| [ADR-026-dry-run-mode.md](../architecture/adr/ADR-026-dry-run-mode.md) | ADR-026 · 演练 / Dry-run 模式 | 更新：当前事实/引用；保留历史证据 |
| [ADR-027-resource-affinity.md](../architecture/adr/ADR-027-resource-affinity.md) | ADR-027 · 资源亲和性 / 地理调度 | 保留：原决策与历史状态 |
| [ADR-028-sensor-wait-node.md](../architecture/adr/ADR-028-sensor-wait-node.md) | ADR-028 · Sensor WAIT 节点 | 更新：当前事实/引用；保留历史证据 |
| [ADR-029-dedicated-spi-worker.md](../architecture/adr/ADR-029-dedicated-spi-worker.md) | ADR-029 · 专用 Task SPI Worker(dual-use 能力隔离) | 保留：原决策与历史状态 |
| [ADR-029-shared-config-defaults-module.md](../architecture/adr/ADR-029-shared-config-defaults-module.md) | ADR-029 · 共享配置基线 batch-defaults.yml | 保留：原决策与历史状态 |
| [ADR-030-content-verifier-spi.md](../architecture/adr/ADR-030-content-verifier-spi.md) | ADR-030 · 产物内容验收 SPI（ContentVerifier） | 保留：原决策与历史状态 |
| [ADR-031-dual-track-pagination.md](../architecture/adr/ADR-031-dual-track-pagination.md) | ADR-031: 双轨分页(Offset + Cursor) | 保留：原决策与历史状态 |
| [ADR-032-four-role-rbac-redesign.md](../architecture/adr/ADR-032-four-role-rbac-redesign.md) | ADR-032 控制台 4 角色 RBAC 重设计 | 保留：原决策与历史状态 |
| [ADR-033-quartz-to-wheel-scheduler.md](../architecture/adr/ADR-033-quartz-to-wheel-scheduler.md) | ADR-033: Quartz 调度器替换为时间轮(HashedWheelTimer)（已撤销） | 保留：原决策与历史状态 |
| [ADR-034-cap-positioning.md](../architecture/adr/ADR-034-cap-positioning.md) | ADR-034:CAP 定位 — 核心链路 CP,只读 / 观测层 AP | 保留：原决策与历史状态 |
| [ADR-035-tenant-self-hosted-worker-sdk.md](../architecture/adr/ADR-035-tenant-self-hosted-worker-sdk.md) | ADR-035 · 平台定位:纯调度面 + 租户自托管 Worker SDK | 更新：当前事实/引用；保留历史证据 |
| [ADR-036-sdk-task-handler-templates.md](../architecture/adr/ADR-036-sdk-task-handler-templates.md) | ADR-036 · batch-worker-sdk 五大业务模板抽象类(Atomic / Import / Export / Process / Dispatch) | 保留：原决策与历史状态 |
| [ADR-037-sdk-checkpoint-resume-and-reliable-commit.md](../architecture/adr/ADR-037-sdk-checkpoint-resume-and-reliable-commit.md) | ADR-037 · batch-worker-sdk 分片级断点续跑与可靠提交协议(checkpoint / commit / 协作 cancel / 流式分片模板) | 更新：当前事实/引用；保留历史证据 |
| [ADR-038-platform-worker-checkpoint-resume.md](../architecture/adr/ADR-038-platform-worker-checkpoint-resume.md) | ADR-038 · 平台 Worker 分片级断点续跑与 chunk 位点补偿式续跑(Import / Export) | 更新：当前事实/引用；保留历史证据 |
| [ADR-039-credential-env-reference.md](../architecture/adr/ADR-039-credential-env-reference.md) | ADR-039 · 凭据字段 envRef 风格 —— 替代关键字扫描的根治方案 | 保留：原决策与历史状态 |
| [ADR-040-batch-manifest-driven-arrival-group.md](../architecture/adr/ADR-040-batch-manifest-driven-arrival-group.md) | ADR-040 · 清单驱动的动态到达组 —— 上游批次清单声明当天预期文件 | 更新：当前事实/引用；保留历史证据 |
| [ADR-041-control-total-continuity-gate.md](../architecture/adr/ADR-041-control-total-continuity-gate.md) | ADR-041 · 控制总额贯穿闸 —— 文件传输完整性的端到端对账 | 保留：原决策与历史状态 |
| [ADR-042-admission-bounded-queue-backpressure.md](../architecture/adr/ADR-042-admission-bounded-queue-backpressure.md) | ADR-042 · 超容准入:硬拒默认 → 有界队列 + 背压 | 更新：当前事实/引用；保留历史证据 |
| [ADR-043-dependency-aware-fire.md](../architecture/adr/ADR-043-dependency-aware-fire.md) | ADR-043 · 依赖感知 fire —— 触发前上游就绪闸 | 更新：当前事实/引用；保留历史证据 |
| [ADR-044-instance-pause-resume-batch-day-serial.md](../architecture/adr/ADR-044-instance-pause-resume-batch-day-serial.md) | ADR-044 · 实例 pause/resume + 批次日严格串行 | 更新：当前事实/引用；保留历史证据 |
| [ADR-045-console-ai-ops-assistant.md](../architecture/adr/ADR-045-console-ai-ops-assistant.md) | ADR-045 · 控制台 AI 运维助手 —— 只读问答的边界与定位 | 保留：原决策与历史状态 |
| [ADR-046-file-bundle-aggregation.md](../architecture/adr/ADR-046-file-bundle-aggregation.md) | ADR-046 · 文件束聚合 —— 在单文件单元之上加一层批量编排(File Bundle Aggregation) | 更新：当前事实/引用；保留历史证据 |
| [ADR-047-workflow-scheduling-and-daily-obligations.md](../architecture/adr/ADR-047-workflow-scheduling-and-daily-obligations.md) | ADR-047：Workflow 调度归属与每日业务义务 | 保留：原决策与历史状态 |
| [ADR-048-workflow-fresh-database-cutover.md](../architecture/adr/ADR-048-workflow-fresh-database-cutover.md) | ADR-048：全新数据库下的 Workflow 与每日连续性统一模型 | 更新：当前事实/引用；保留历史证据 |
| [ADR-049-replay-operation-scopes.md](../architecture/adr/ADR-049-replay-operation-scopes.md) | ADR-049：Replay 操作范围与身份 | 更新：当前事实/引用；保留历史证据 |
| [README.md](../architecture/adr/README.md) | ADR 索引（架构决策记录） | 更新：当前事实/引用；保留历史证据 |
| [application-governance.md](../architecture/application-governance.md) | 应用治理收口（2026-09-02） | 保留：本次未发现直接模型冲突 |
| [architecture-truth.md](../architecture/architecture-truth.md) | 当前架构事实入口 | 更新：当前事实、图/字段或索引 |
| [bounded-context-rules.md](../architecture/bounded-context-rules.md) | Bounded Context 边界规则(P1-A Stage 1) | 保留：本次未发现直接模型冲突 |
| [core-model.md](../architecture/core-model.md) | 核心模型统一口径 | 更新：当前事实、图/字段或索引 |
| [engineering-maturity-roadmap.md](../architecture/engineering-maturity-roadmap.md) | 工程成熟度路线图 | 保留：本次未发现直接模型冲突 |
| [event-routing-policy.md](../architecture/event-routing-policy.md) | 异步事件路由政策 | 更新：当前事实、图/字段或索引 |
| [governance-tables-boundary.md](../architecture/governance-tables-boundary.md) | Governance 表职责边界 — 命名相似但职责正交的表对 | 保留：本次未发现直接模型冲突 |
| [heavy-workload-guarantees.md](../architecture/heavy-workload-guarantees.md) | 重任务容量与执行保障 | 更新：当前事实、图/字段或索引 |
| [kafka-topic-plan.md](../architecture/kafka-topic-plan.md) | Kafka Topic 设计规范 | 更新：当前事实、图/字段或索引 |
| [notification-and-audit-boundary.md](../architecture/notification-and-audit-boundary.md) | 通知与审计职责边界 | 更新：当前事实、图/字段或索引 |
| [outbox-architecture.md](../architecture/outbox-architecture.md) | Outbox 子系统总览 — 主表与副表关系 | 更新：当前事实、图/字段或索引 |
| [pipeline-vs-workflow-boundary.md](../architecture/pipeline-vs-workflow-boundary.md) | pipeline_* 与 workflow_* 职责边界 | 更新：当前事实、图/字段或索引 |
| [project-structure.md](../architecture/project-structure.md) | file-batch-system 项目结构 | 更新：当前事实、图/字段或索引 |
| [runtime-compatibility-contract.md](../architecture/runtime-compatibility-contract.md) | 前后端运行时兼容约束 | 保留：本次未发现直接模型冲突 |
| [runtime-module-communication.md](../architecture/runtime-module-communication.md) | 运行时模块通信拓扑 | 更新：当前事实、图/字段或索引 |
| [security-model.md](../architecture/security-model.md) | 系统安全模型 | 更新：当前事实、图/字段或索引 |
| [system-flow-overview.md](../architecture/system-flow-overview.md) | 系统总流程 — 一眼看懂 file-batch-system 怎么运转 | 更新：当前事实、图/字段或索引 |
| [system-scope-boundary.md](../architecture/system-scope-boundary.md) | 系统职责范围分析 — 批量调度系统的边界守护 | 保留：本次未发现直接模型冲突 |
| [worker-plugins.md](../architecture/worker-plugins.md) | Worker 平台 + 插件（导入 / 导出 / 分发） | 保留：本次未发现直接模型冲突 |
| [workflow-dependency-guide.md](../architecture/workflow-dependency-guide.md) | 作业依赖与编排指南 — Workflow DAG | 更新：当前事实、图/字段或索引 |

### design/

| 文件 | 主题/作用 | 本轮处理 |
|---|---|---|
| [README.md](../design/README.md) | 设计文档索引 | 更新：当前事实、图/字段或索引 |
| [api-abuse-protection.md](../design/api-abuse-protection.md) | 接口防盗刷 / 防滥用设计 | 保留：本次未发现直接模型冲突 |
| [asset-partition-readiness.md](../design/asset-partition-readiness.md) | Asset Partition Readiness | 保留：本次未发现直接模型冲突 |
| [batch-classification-and-gaps.md](../design/batch-classification-and-gaps.md) | 批量类型分类法与系统缺口分析 | 更新：当前事实/引用；保留历史证据 |
| [batch-day-capability-design.md](../design/batch-day-capability-design.md) | 批量日与批量运行平台能力设计总结 | 更新：当前事实/引用；保留历史证据 |
| [batch-day-design.md](../design/batch-day-design.md) | 批量日设计方案（完整版） | 更新：当前事实、图/字段或索引 |
| [batch-day-timezone-dst-optimized-design.md](../design/batch-day-timezone-dst-optimized-design.md) | 批量日、时区与冬夏令时优化设计 | 更新：当前事实、图/字段或索引 |
| [bulk-user-provisioning.md](../design/bulk-user-provisioning.md) | 批量账号开户后端设计 | 保留：本次未发现直接模型冲突 |
| [capability-assessment.md](../design/capability-assessment.md) | 20. 批量调度能力评估 | 保留：历史/设计定位已明确 |
| [console-login-bruteforce-protection.md](../design/console-login-bruteforce-protection.md) | Console 登录防暴力破解 + 验证码 设计方案 | 保留：本次未发现直接模型冲突 |
| [console-sidebar-menu-tree.md](../design/console-sidebar-menu-tree.md) | Console Sidebar 菜单树 | 保留：本次未发现直接模型冲突 |
| [data-model-ddl.md](../design/data-model-ddl.md) | 14. 数据模型与 PostgreSQL 表结构设计 | 更新：当前事实/引用；保留历史证据 |
| [database-schema-governance.md](../design/database-schema-governance.md) | 数据库表结构治理 | 更新：当前事实、图/字段或索引 |
| [database-schema-guide.md](../design/database-schema-guide.md) | 数据库表目录与关系总览 | 更新：当前事实、图/字段或索引 |
| [delete-strategy.md](../design/delete-strategy.md) | 删除策略设计 | 保留：本次未发现直接模型冲突 |
| [dispatch-secret-version-runtime-lifecycle.md](../design/dispatch-secret-version-runtime-lifecycle.md) | Dispatch 密钥版本运行时语义 | 更新：当前事实、图/字段或索引 |
| [file-integrity-recovery.md](../design/file-integrity-recovery.md) | 文件完整性与故障恢复方案 | 保留：本次未发现直接模型冲突 |
| [file-integrity-sidecar-manifest-design.md](../design/file-integrity-sidecar-manifest-design.md) | 文件完整性 sidecar manifest 设计说明 | 保留：本次未发现直接模型冲突 |
| [file-pipeline-design.md](../design/file-pipeline-design.md) | 9. 文件处理链路设计 | 保留：本次未发现直接模型冲突 |
| [i18n.md](../design/i18n.md) | 国际化（i18n）规范 | 保留：本次未发现直接模型冲突 |
| [import-export-column-mapping-default-inference.md](../design/import-export-column-mapping-default-inference.md) | 列映射配置体验:IMPORT 默认推断,EXPORT 列归 SELECT | 保留：本次未发现直接模型冲突 |
| [legacy-design-chapter-map.md](../design/legacy-design-chapter-map.md) | mega 设计文档章节 → 当前位置导航表 | 保留：历史/设计定位已明确 |
| [logging-architecture.md](../design/logging-architecture.md) | 日志体系架构 | 保留：本次未发现直接模型冲突 |
| [multi-tenant-and-security.md](../design/multi-tenant-and-security.md) | 多租户与安全设计 | 保留：本次未发现直接模型冲突 |
| [object-storage-abstraction.md](../design/object-storage-abstraction.md) | 对象存储抽象（Phase 2 设计方案） | 保留：本次未发现直接模型冲突 |
| [partition-idempotency-decision.md](../design/partition-idempotency-decision.md) | 分区表与全局幂等身份 | 更新：当前事实、图/字段或索引 |
| [pipeline-stage-progress-display.md](../design/pipeline-stage-progress-display.md) | Pipeline Step 进度展示与 SSE 刷新设计 | 保留：本次未发现直接模型冲突 |
| [pipeline-vs-workflow-definition.md](../design/pipeline-vs-workflow-definition.md) | Pipeline vs Workflow vs Job 边界 | 更新：当前事实、图/字段或索引 |
| [redis-usage-design.md](../design/redis-usage-design.md) | Redis 使用设计 | 保留：本次未发现直接模型冲突 |
| [runtime-default-parameters.md](../design/runtime-default-parameters.md) | 默认运行参数基线（§20.11） | 更新：当前事实、图/字段或索引 |
| [sdk-task-type-configuration.md](../design/sdk-task-type-configuration.md) | 自定义 taskType 作业配置设计 | 更新：当前事实/引用；保留历史证据 |
| [sla-and-quality.md](../design/sla-and-quality.md) | 运行质量与 SLA 设计 | 更新：当前事实、图/字段或索引 |
| [status-state-machines.md](../design/status-state-machines.md) | 状态机汇总 | 更新：当前事实、图/字段或索引 |
| [task-spi-design.md](../design/task-spi-design.md) | Task SPI / Plugin 化设计(P0) | 更新：当前事实/引用；保留历史证据 |
| [tech-stack-and-principles.md](../design/tech-stack-and-principles.md) | 技术栈与设计原则 | 保留：本次未发现直接模型冲突 |
| [tenant-config-package-excel-9plus2-design.md](../design/tenant-config-package-excel-9plus2-design.md) | 租户配置包 Excel 9+2 设计 | 更新：当前事实、图/字段或索引 |
| [time-date-semantics.md](../design/time-date-semantics.md) | 时间与日期语义 | 更新：当前事实、图/字段或索引 |
| [timezone-and-dst-design.md](../design/timezone-and-dst-design.md) | 批量平台时区与夏令时设计说明 | 更新：当前事实、图/字段或索引 |
| [worker-deployment-models.md](../design/worker-deployment-models.md) | Worker 部署模型:平台 worker vs 自托管 SDK | 更新：当前事实、图/字段或索引 |
| [workflow-dag-designer.md](../design/workflow-dag-designer.md) | Workflow DAG 编辑器方案 | 更新：当前事实、图/字段或索引 |
| [workflow-dynamic-fanout.md](../design/workflow-dynamic-fanout.md) | Workflow 分区契约与历史 TASK fan-out | 更新：当前事实/引用；保留历史证据 |

### api/

| 文件 | 主题/作用 | 本轮处理 |
|---|---|---|
| [README.md](../api/README.md) | API 文档索引 | 更新：当前事实、图/字段或索引 |
| [console-api-protocol.md](../api/console-api-protocol.md) | Console API 协议 | 更新：当前事实、图/字段或索引 |
| [console-api.openapi.yaml](../api/console-api.openapi.yaml) | 机读协议/共享常量契约 | 保留：协议行为值不变，执行静态校验 |
| [orchestrator-internal-openapi-sync.md](../api/orchestrator-internal-openapi-sync.md) | orchestrator internal OpenAPI ↔ controller 同步守护 | 更新：当前事实、图/字段或索引 |
| [orchestrator-internal.openapi.yaml](../api/orchestrator-internal.openapi.yaml) | 机读协议/共享常量契约 | 更新：当前事实、图/字段或索引 |
| [01-register-success.json](../api/sdk-contract-fixtures/01-register-success.json) | 黄金行为用例：register-success | 保留：协议行为值不变，执行静态校验 |
| [02-register-conflict-idempotent.json](../api/sdk-contract-fixtures/02-register-conflict-idempotent.json) | 黄金行为用例：register-conflict-idempotent | 保留：协议行为值不变，执行静态校验 |
| [03-heartbeat-directive-normal.json](../api/sdk-contract-fixtures/03-heartbeat-directive-normal.json) | 黄金行为用例：heartbeat-directive-normal | 保留：协议行为值不变，执行静态校验 |
| [04-heartbeat-directive-draining.json](../api/sdk-contract-fixtures/04-heartbeat-directive-draining.json) | 黄金行为用例：heartbeat-directive-draining | 保留：协议行为值不变，执行静态校验 |
| [05-heartbeat-directive-paused.json](../api/sdk-contract-fixtures/05-heartbeat-directive-paused.json) | 黄金行为用例：heartbeat-directive-paused | 保留：协议行为值不变，执行静态校验 |
| [06-heartbeat-next-interval-hint.json](../api/sdk-contract-fixtures/06-heartbeat-next-interval-hint.json) | 黄金行为用例：heartbeat-next-interval-hint | 保留：协议行为值不变，执行静态校验 |
| [07-claim-401-fail-fast.json](../api/sdk-contract-fixtures/07-claim-401-fail-fast.json) | 黄金行为用例：claim-401-fail-fast | 保留：协议行为值不变，执行静态校验 |
| [08-claim-409-idempotent-success.json](../api/sdk-contract-fixtures/08-claim-409-idempotent-success.json) | 黄金行为用例：claim-409-idempotent-success | 保留：协议行为值不变，执行静态校验 |
| [09-report-5xx-retry-backoff.json](../api/sdk-contract-fixtures/09-report-5xx-retry-backoff.json) | 黄金行为用例：report-5xx-retry-backoff | 保留：协议行为值不变，执行静态校验 |
| [10-renew-cancel-requested.json](../api/sdk-contract-fixtures/10-renew-cancel-requested.json) | 黄金行为用例：renew-cancel-requested | 保留：协议行为值不变，执行静态校验 |
| [11-kafka-partition-pause-on-capacity.json](../api/sdk-contract-fixtures/11-kafka-partition-pause-on-capacity.json) | 黄金行为用例：kafka-partition-pause-on-capacity | 保留：协议行为值不变，执行静态校验 |
| [12-stop-with-timeout.json](../api/sdk-contract-fixtures/12-stop-with-timeout.json) | 黄金行为用例：stop-with-timeout | 保留：协议行为值不变，执行静态校验 |
| [13-report-field-names-redline.json](../api/sdk-contract-fixtures/13-report-field-names-redline.json) | 黄金行为用例：report-field-names-redline | 保留：协议行为值不变，执行静态校验 |
| [14-partition-invocation-id-passthrough.json](../api/sdk-contract-fixtures/14-partition-invocation-id-passthrough.json) | 黄金行为用例：partition-invocation-id-passthrough | 保留：协议行为值不变，执行静态校验 |
| [15-partition-invocation-id-absent-when-unclaimed.json](../api/sdk-contract-fixtures/15-partition-invocation-id-absent-when-unclaimed.json) | 黄金行为用例：partition-invocation-id-absent-when-unclaimed | 保留：协议行为值不变，执行静态校验 |
| [16-kafka-schema-version-missing-accept.json](../api/sdk-contract-fixtures/16-kafka-schema-version-missing-accept.json) | 黄金行为用例：kafka-schema-version-missing-accept | 保留：协议行为值不变，执行静态校验 |
| [17-kafka-schema-version-v2-accept.json](../api/sdk-contract-fixtures/17-kafka-schema-version-v2-accept.json) | 黄金行为用例：kafka-schema-version-v2-accept | 保留：协议行为值不变，执行静态校验 |
| [18-kafka-schema-version-v3-reject.json](../api/sdk-contract-fixtures/18-kafka-schema-version-v3-reject.json) | 黄金行为用例：kafka-schema-version-v3-reject | 保留：协议行为值不变，执行静态校验 |
| [19-register-apikey-in-header-not-body.json](../api/sdk-contract-fixtures/19-register-apikey-in-header-not-body.json) | 黄金行为用例：register-apikey-in-header-not-body | 保留：协议行为值不变，执行静态校验 |
| [20-report-idempotency-key-header.json](../api/sdk-contract-fixtures/20-report-idempotency-key-header.json) | 黄金行为用例：report-idempotency-key-header | 保留：协议行为值不变，执行静态校验 |
| [21-claim-4xx-client-error-no-failfast.json](../api/sdk-contract-fixtures/21-claim-4xx-client-error-no-failfast.json) | 黄金行为用例：claim-4xx-client-error-no-failfast | 保留：协议行为值不变，执行静态校验 |
| [22-renew-404-not-found-give-up.json](../api/sdk-contract-fixtures/22-renew-404-not-found-give-up.json) | 黄金行为用例：renew-404-not-found-give-up | 保留：协议行为值不变，执行静态校验 |
| [23-claim-4xx-fifth-fail-fast.json](../api/sdk-contract-fixtures/23-claim-4xx-fifth-fail-fast.json) | 黄金行为用例：claim-4xx-fifth-fail-fast | 保留：协议行为值不变，执行静态校验 |
| [24-report-idempotency-key-minted-not-fixed.json](../api/sdk-contract-fixtures/24-report-idempotency-key-minted-not-fixed.json) | 黄金行为用例：report-idempotency-key-minted-not-fixed | 保留：协议行为值不变，执行静态校验 |
| [25-heartbeat-503-no-backoff.json](../api/sdk-contract-fixtures/25-heartbeat-503-no-backoff.json) | 黄金行为用例：heartbeat-503-no-backoff | 保留：协议行为值不变，执行静态校验 |
| [26-heartbeat-directive-degraded.json](../api/sdk-contract-fixtures/26-heartbeat-directive-degraded.json) | 黄金行为用例：heartbeat-directive-degraded | 保留：协议行为值不变，执行静态校验 |
| [27-heartbeat-desired-max-concurrent.json](../api/sdk-contract-fixtures/27-heartbeat-desired-max-concurrent.json) | 黄金行为用例：heartbeat-desired-max-concurrent | 保留：协议行为值不变，执行静态校验 |
| [28-kafka-paused-task-type-drop.json](../api/sdk-contract-fixtures/28-kafka-paused-task-type-drop.json) | 黄金行为用例：kafka-paused-task-type-drop | 保留：协议行为值不变，执行静态校验 |
| [29-kafka-ignore-unknown-field.json](../api/sdk-contract-fixtures/29-kafka-ignore-unknown-field.json) | 黄金行为用例：kafka-ignore-unknown-field | 保留：协议行为值不变，执行静态校验 |
| [30-kafka-decode-error-commit-skip.json](../api/sdk-contract-fixtures/30-kafka-decode-error-commit-skip.json) | 黄金行为用例：kafka-decode-error-commit-skip | 保留：协议行为值不变，执行静态校验 |
| [README.md](../api/sdk-contract-fixtures/README.md) | SDK Contract Fixtures(language-agnostic) | 保留：本次未发现直接模型冲突 |
| [fixture-schema.json](../api/sdk-contract-fixtures/fixture-schema.json) | 黄金样本schema | 保留：协议行为值不变，执行静态校验 |
| [validate.sh](../api/sdk-contract-fixtures/validate.sh) | 黄金样本schema校验入口 | 保留：协议行为值不变，执行静态校验 |
| [sdk-shared-constants.yaml](../api/sdk-shared-constants.yaml) | 机读协议/共享常量契约 | 保留：协议行为值不变，执行静态校验 |

### sdk/

| 文件 | 主题/作用 | 本轮处理 |
|---|---|---|
| [README.md](../sdk/README.md) | SDK 文档索引 | 更新：当前事实、图/字段或索引 |
| [RELEASING.md](../sdk/RELEASING.md) | SDK 发布手册(RELEASING) | 保留：本次未发现直接模型冲突 |
| [byo-conformance-contract.md](../sdk/byo-conformance-contract.md) | BYO SDK Conformance Contract（防漂移契约) | 保留：本次未发现直接模型冲突 |
| [byo-sdk-guide.md](../sdk/byo-sdk-guide.md) | BYO(Bring Your Own)SDK 接入指南 | 更新：当前事实、图/字段或索引 |
| [config-reference.md](../sdk/config-reference.md) | SDK 配置总表(五语言) | 更新：当前事实、图/字段或索引 |
| [ipv6-happy-eyeballs.md](../sdk/ipv6-happy-eyeballs.md) | SDK IPv6 / Happy Eyeballs | 保留：本次未发现直接模型冲突 |
| [local-e2e-coverage.md](../sdk/local-e2e-coverage.md) | SDK 本地全链路覆盖(local e2e coverage) | 更新：当前事实/引用；保留历史证据 |
| [onboarding-journey.md](../sdk/onboarding-journey.md) | SDK 接入端到端旅程 — 从 0 到第一个 task 跑通 + 灰度切 buildId | 更新：当前事实、图/字段或索引 |
| [quickstart.md](../sdk/quickstart.md) | SDK Quickstart — 5 分钟跑起一个租户自托管 worker | 更新：当前事实、图/字段或索引 |
| [sdk-parity-matrix.md](../sdk/sdk-parity-matrix.md) | 五语言 SDK 对齐矩阵(亲核版) | 更新：当前事实/引用；保留历史证据 |
| [troubleshooting.md](../sdk/troubleshooting.md) | SDK Troubleshooting — 常见错误自助查 | 保留：本次未发现直接模型冲突 |
| [wire-protocol.md](../sdk/wire-protocol.md) | SDK ↔ Orchestrator 通讯协议 / 故障感知 | 更新：当前事实、图/字段或索引 |

### dict/

| 文件 | 主题/作用 | 本轮处理 |
|---|---|---|
| [README.md](../dict/README.md) | 字典索引（dict/） | 更新：当前事实、图/字段或索引 |
| [config-keys.md](../dict/config-keys.md) | 配置键字典（自动生成） | 更新：当前事实、图/字段或索引 |
| [error-codes.md](../dict/error-codes.md) | 错误码字典 | 保留：本次未发现直接模型冲突 |
| [glossary.md](../dict/glossary.md) | 业务术语字典 | 更新：当前事实、图/字段或索引 |

## 6. 变更统计与验证记录

按任务开始的168文件快照，本轮更新 **76 个资产文件**：architecture 39、design 22、api 4、sdk 8、dict 3。本报告与审计目录索引不计入168文件分母。并发会话后续变更不由本报告背书。

### 6.1 已完成的本地静态检查

| 实际命令 / 检查 | 结果 |
|---|---|
| `python3 scripts/ci/check-docs-structure.py` | 通过；目录索引、文件用途与仓库内链接 |
| `python3 scripts/ci/check-doc-timestamp-policy.py` | 通过；带日期282、无日期281，当前资产无带日期遗留 |
| `python3 scripts/ci/check-code-doc-references.py` | 通过；代码与文档路径引用 |
| `python3 scripts/ci/check-terminology-doc-sync.py` | 通过；核心枚举/术语同步 |
| `python3 scripts/ci/check-console-openapi-paths.py` | 通过；427个Console方法/路径路由 |
| `python3 scripts/codegen/gen-error-codes-dict.py --check` | 通过；17条错误码与生成物/i18n一致 |
| `python3 /tmp/check-asset-contracts-20261024.py`（本轮临时只读核对） | 3份YAML无重复键、内部引用完整；internal为51路径/80schema、54方法/路径匹配；12个相关record字段集相等（EffectiveTaskConfig 34字段） |
| `node /tmp/check-architecture-mermaid-20261024.mjs`（本轮临时只读解析） | 48段Mermaid全部语法通过；不等同于逐图浏览器渲染验收 |
| `node /tmp/check-sdk-fixtures-ajv-20261024.cjs`（本轮临时只读校验） | 使用现有Ajv 8.20.0按原Draft 2020-12 schema校验，30份黄金样本全部通过 |
| `git diff --check -- docs/architecture docs/design docs/api docs/sdk docs/dict docs/audit/README.md docs/plans/todo-master.md docs/plans/workflow-daily-integration-plan-2026-10-14.md docs/changelog.md` | 通过；相应文档差异无空白错误 |
| 任务前快照对照 | 核查清单168行；33份sdk-contract-fixtures目录资产内容全部保持不变；长期资产更新76文件 |

临时核对脚本用于本次证据，不作为已新增CI守卫。现有internal OpenAPI工作流仍是log-only路径检查；本次额外方法/字段核对没有悄悄改变CI门禁。

### 6.2 首失败与处理

- 结构检查首轮报告新审计入口尚未落档；创建本报告及README入口后复验通过。
- Mermaid首轮发现总流程图中既有 `trigger_outbox_event` 节点括号/标签未正确引用；修复后48段全部解析通过。
- `bash docs/api/sdk-contract-fixtures/validate.sh` 首轮退出2：默认Python缺少 `jsonschema`；使用现有bundled Python复试同样缺依赖。未安装依赖或削弱schema，改用本地已有Ajv8的Draft 2020-12实现验证同一schema与全部30份原样本。**Python脚本入口本身仍未成功运行**，不能记录为该入口通过。
- 临时DTO提取器最初漏算末字段并因人工预计数量不符停止；修正提取方式，最终按字段集合逐项相等核对，包含ReplayExecutionGuard。未通过修改协议迎合错误计数。

### 6.3 未执行的验证

本次没有修改Java/SQL/SDK运行实现，没有重跑Java编译、单测/集成测试、SDK五语言transport、SIM或真实前后端联调；没有执行文档站点完整构建、远端CI、容量、通知提供商或目标环境验收。其它会话的构建/回归仍按其原证据和源码版本记录。本次未提交、推送或创建PR。

