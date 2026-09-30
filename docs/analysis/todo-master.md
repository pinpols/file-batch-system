# TODO Master · 当前待办唯一索引

> 核查日期：2026-09-30。本文只登记当前仍有效的事项；`docs/archive/` 的历史待办不计入本表。
> 状态分类、证据要求和归档规则见 [`../standards/document-governance.md`](../standards/document-governance.md)。

> 本文早期的统计数字和日期快照可能已过期；后续以事项表、证据路径和最后核查日期为准，不以历史总数为准。

> 本轮复核结论：AI、使用率统计、维护/降级三项均已有部分基础能力，不能再按“从零未实现”处理；剩余项已在各表中拆成明确缺口。五类 Worker 的核心 Runtime/SPI 和本地 Sim 已完成，仍需区分本地证据与 staging/生产证据。最近一次本地 Sim 证据：`logs/runs/sim-harness/sim-harness-20260930-095359-e6f7b3887/sim-summary.txt`，脚本 04–28 全部通过。

---

## 一、状态总览

本表不再维护“完成 X 项”的汇总数字。数字会随历史快照、重复计划和外部验证状态变化而失真；当前判断以每条事项的状态、证据和最后核查日期为准。

| 状态 | 当前口径 |
|---|---|
| ✅ **完成** | 有代码、测试、门禁或验证记录支撑；完成项不再重复列入待办 |
| ⏳ **当前待做** | 本地仍有明确交付物，且没有被更新文档或决策替代 |
| 🔒 **外部阻塞** | 本地材料已准备，等待 staging、运维、DBA、业务方或外部凭据 |
| 🟡 **暂缓** | 已明确不立即实施，并记录触发条件和复审周期 |
| ❌ **不做** | 明确超出系统边界或收益不足，仅保留决策理由 |

### 当前核查边界（2026-09-27）

以下事项仍可从现行文档确认存在，但不能仅凭历史计划宣称“代码未完成”：

| 主题 | 当前归类 | 权威来源 |
|---|---|---|
| staging 真实恢复、容量、发布与回滚留档 | 🔒 外部阻塞 | [`full-project-test-plan.md`](../testing/full-project-test-plan.md) |
| 生产 PG/Kafka/Redis HA、PITR 和真实故障演练 | 🔒 外部阻塞 | [`ha-readiness.md`](../runbook/ha-readiness.md) |
| 前端 dry-run、跨日 DAG、批次日 replay 页面 | ✅ 已完成 | 本表 §二 FE-1/2/3 |
| CI dry-run guard、dry-run 审计与指标维度 | ✅ 已完成 | `DryRunGuardConventionTest`、`TAG_DRY_RUN` 及 worker/plugin 守护已落地 |
| Quartz → Wheel 时间轮替换 | ❌ 不做（旧提案已撤销） | [ADR-033](../architecture/adr/ADR-033-quartz-to-wheel-scheduler.md)；当前继续使用 Quartz |
| 冷热分层、资源亲和 | 🟡 暂缓 | 对应 ADR 的触发条件 |

其余来源文档中的“未完成 / TODO / 缺口”必须按 [`document-governance.md`](../standards/document-governance.md) 复核后，才能加入当前待办。

### 🟡 暂缓清单(2026-05-21 集中索引)

| 项 | ADR / 文档 | 触发条件 |
|---|---|---|
| ADR-024 冷热分层 | [ADR-024](../architecture/adr/ADR-024-archive-tiering.md) §"实施触发条件" | 数据量 / 备份 / 监管阈值达 |
| ADR-027 资源亲和 / 地理调度 | [ADR-027](../architecture/adr/ADR-027-resource-affinity.md) | K8s 自研调度需求出现 |
| ADR-022 v0.2 `*_history` 影子表 + OSS 对象锁 | [ADR-022](../architecture/adr/ADR-022-forensic-audit-bundle.md) status | 7 年保留合规要求触发 |
| LIC-2 SBOM 嵌入 artifact | todo-master §H | 合规审计 / 客户 SBOM 要求 |

**环境分布**（可执行性切片）：

| 类别 | 当前结论 | 说明 |
|---|---|---|
| ✅ **本地可独立完成** | 本轮已完成 | RLS 运行账号校验、Trigger 真入口 E2E、Kafka 恢复验证、SDK 配置对齐和 Python 统一入口均有代码或测试证据 |
| 🔒 **本地不能做（挂起）** | 见 §九 | 需 ops / staging / prod / DBA / BIZ 配合；不使用易失真的汇总数字 |

---

## 二、ADR 优先级三阶段进展（priority-scope 镜像）

权威源：[`archive/analysis/adr-012-021-027-priority-scope-2026-05-06.md`](../archive/analysis/adr-012-021-027-priority-scope-2026-05-06.md) + 各 ADR 顶部"范围边界（Scope Discipline）"小节 + docs/agent-baseline.md "ADR 实施范围纪律" 章。

### 第 1 阶段 P0（已落 backend）✅

| ADR | 主题 | 落地证据 |
|---|---|---|
| ADR-012 | 失败分类 | V111 |
| ADR-023 | 多日历联动 | V112-V114 |
| ADR-025 | Workflow 静态校验 | 15 条规则全员到齐（V1-V15） |

### 第 2 阶段 P1（已落 backend）✅

| ADR | 主题 | 落地证据 |
|---|---|---|
| ADR-021 | 数据对账 v1.0 | V118 + DataQualityCheckExecutor + EFFECTIVE gate |
| ADR-022 | Forensic v0.1 | V116 + 同步 bundle + SHA-256 |
| ADR-026 | dry-run 全链路 | V115 + V117 + DryRunGuard SPI + L1/L2/L3 service + SUCCESS_DRY_RUN / FAILED_DRY_RUN 终态 + L3 真接 SQL EXPLAIN / MinIO bucketExists / HTTP HEAD |

### 第 3 阶段（暂缓）

| ADR | 主题 | 触发条件未达 |
|---|---|---|
| ADR-024 | 冷热分层 | archive 行数未到阈值 |
| ADR-027 | 资源亲和 | worker_group ≥ 8 / 异构硬件 / 多机房 / 合规隔离 等条件未出现 |

### 前端能力（已完成）✅

| ID | 主题 | 完成证据 |
|---|---|---|
| **FE-1** | ADR-026 Console UI 演练模式 | `batch-console/src/components/dialogs/DryRunPlanDialog.vue` + Job 定义详情接线 |
| **FE-2** | ADR-018 跨日 DAG Console UI | `WorkflowMermaidViewer.vue` + `crossDayMermaid.test.ts` |
| **FE-3** | ADR-020/026 批次日重放与整批量日演练 | `BatchDayReplay.vue` 支持 REPLAY/DRY_RUN、EXISTING_INSTANCES/SCHEDULE_PLAN，提交归一化单测覆盖无副作用策略 |

### 横切关注点（历史编号，已完成）✅

| ID | 主题 |
|---|---|
| ~~**CC-1**~~ | CI lint 守护：step plugin 必经 DryRunGuard | ✅ |
| ~~**CC-2**~~ | audit + metric label 加 dry_run 维度 | ✅ |

> **状态校准（2026-09-11）**：CC-1/CC-2 已完成。证据为 `batch-worker-core` 的
> `DryRunGuardConventionTest`、`batch-common` 的 `BatchMetricsNames.TAG_DRY_RUN` 及相关审计字段。
> 本表保留编号用于追溯，不再作为当前待办。

---

## 三、⏳ 待做

### A. POSITIONAL-ARGS 治理（V6-P2-POSITIONAL-ARGS）· P2 · ✅ 已闭环

> 状态：v4 已闭环，并行会话产出 + 守护测试到位。历史方案见 [`../archive/analysis/positional-args-cleanup-plan.md`](../archive/analysis/positional-args-cleanup-plan.md)。docs/agent-baseline.md "调用方约束" 子节由本方案沉淀。

历史详细计划项（POS-1 ~ POS-5）已全部完成，归 §五。

### B. Query Record 工厂 · P2 · ✅ 已闭环

QF-1/QF-2/QF-3 全部完成，包含守护测试 `QueryRecordConstructionConventionTest`。归 §五。

### C. ADR-009 Stage 4（业务方按需触发）· P2 deferred

| ID | 主题 | 来源 |
|---|---|---|
| **ADR9-S4** | 7 workflow 配 DSL 引用上游节点 output | hardening-backlog v6 / ADR-009-workflow-param-dsl.md |

> 状态：Stage 1 / 1.2 / 2 / 3 代码 ✅（`DefaultWorkflowNodeDispatchService.mergeNodeParams` 集成 `WorkflowParamResolver` + 4 worker `*StepExecutionAdapter` 填 `NODE_OUTPUTS`）。Stage 4 deferred — 现 seed 节点间 `mergeUpstreamPartitionOutputs` 自动透传 `fileId` 已够用，业务方设计跨节点参数串联时按 §10 文档配。

### D. ADR-010 灰度 + 物删 · 已完成（历史记录）

> ADR-010 的异步 Kafka 路径已固化为唯一路径，灰度开关和同步 HTTP 适配器已删除。以下旧清单不再是待办或操作指引；当前架构以 [`ADR-010`](../architecture/adr/ADR-010-trigger-async-decoupling.md) 与 [`Trigger 运维手册`](../runbook/trigger-operations.md) 为准。

| ID | 主题 | 来源 | 关联门禁 |
|---|---|---|---|
| **ADR10-S6/S7** | 灰度、唯一异步路径及旧同步入口清理 | ADR-010 实施后记 | 已完成；不再执行旧灰度 SOP |

> 注意：旧版本评估快照中的分阶段状态只用于还原历史，不应重新登记为当前待办。

### E. Quartz → HashedWheelTimer · 已撤销（历史记录）

> [ADR-033](../architecture/adr/ADR-033-quartz-to-wheel-scheduler.md) 已于 2026-07-23 标记 `Superseded`，Wheel 运行路径已移除，当前统一使用 Quartz。下表旧切换任务与阈值均已失效，不是待办；若未来重新评估，须基于新的容量证据另立决策。
>
> 以下仅保留原计划条目作为历史记录，不应执行或纳入当前待办。

| ID | 主题 | 来源 |
|---|---|---|
| **QZ-pre-1** | 业务方明确 cron 精度 SLA | quartz-replacement-design:848 |
| **QZ-pre-2** | cron 兼容性扫描确认 L/W/# 表达式为 0 | quartz-replacement-design:849 |
| **QZ-pre-3** | trigger_runtime_state schema DBA 评审 | quartz-replacement-design:850 |
| **QZ-pre-4** | trigger_request fire 唯一约束不冲突验证 | quartz-replacement-design:851 |
| **QZ-prep-1** | Redis ShedLock 在 trigger 模块就位 | quartz-replacement-design:855 |
| **QZ-prep-2** | Quartz `auto-start=false` 切换路径验证 | quartz-replacement-design:856 |
| **QZ-prep-3** | 4 个 Quartz health metric 在 Grafana 显示 | quartz-replacement-design:857 |
| **QZ-decision-1** | 集成测试矩阵全部通过 | quartz-replacement-design:861 |
| **QZ-decision-2** | 性能测试达标 | quartz-replacement-design:862 |
| **QZ-decision-3** | failover IT 100 次循环无双 fire / 漏 fire | quartz-replacement-design:863 |
| **QZ-stage-1** | Staging 环境跑 2 周无回归 | quartz-replacement-design:867 |
| **QZ-stage-2** | 生产灰度方案制定与验证 | quartz-replacement-design:868 |
| **QZ-stage-3** | 监控告警 3 项就位（QPS/lag/duplicate）| quartz-replacement-design:869 |
| **QZ-rollback-1** | 回滚配置项验证过 | quartz-replacement-design:873 |
| **QZ-rollback-2** | Quartz 数据迁回 SQL 验证 | quartz-replacement-design:874 |
| **QZ-rollback-3** | trigger_runtime_state 表保留不删 | quartz-replacement-design:875 |

### F. Workflow 设计规范 8 校验项 · P1

> 性质：设计 review checklist，非实施工作。Workflow 写入数据库前 PR review 时勾选。

| ID | 主题 | 来源 |
|---|---|---|
| **WF-design-1** | GATEWAY 节点显式写 joinMode | workflow-dependency-guide:323 |
| **WF-design-2** | joinMode=N_OF 必须带 joinThreshold | workflow-dependency-guide:324 |
| **WF-design-3** | 非 START 节点至少一条入边 | workflow-dependency-guide:325 |
| **WF-design-4** | 非 END 节点至少一条出边 | workflow-dependency-guide:326 |
| **WF-design-5** | JOB 节点 related_job_code 有效性验证 | workflow-dependency-guide:327 |
| **WF-design-6** | CONDITION 边 condition_expr 必须配置 | workflow-dependency-guide:328 |
| **WF-design-7** | Workflow 不能有循环 | workflow-dependency-guide:329 |
| **WF-design-8** | workflow definition enabled=true | workflow-dependency-guide:330 |

### G. 删除策略决策清单 · P1 · ✅ 已落地为 PR 模板

5 项 checklist 嵌入 `.github/PULL_REQUEST_TEMPLATE.md` "涉及删除语义的接口" 段，reviewer PR-time 勾选即可。同模板顺带嵌 4 类常见 review checklist（console-api / 字典 / 方法参数 / i18n / 规范条款）。

### G2. REST / Command API Toggle 语义治理 · P2 · ✅ 已完成

| ID | 主题 | 来源 | 状态 |
|---|---|---|---|
| ~~**API-TOGGLE-1**~~ | 6 个 `POST .../toggle?enabled=` 接口命名与显式状态契约收敛 | [`../backlog/rest-command-api-toggle-governance-2026-09-26.md`](../backlog/rest-command-api-toggle-governance-2026-09-26.md) | ✅ 2026-09-27 统一改为 `PATCH .../enabled`，前端及测试已切换，旧接口已删除 |

### G3. 批量平台能力演进 · P0/P1/P2 · 🟡 部分完成，证据待收口

| ID | 主题 | 来源 | 状态 |
|---|---|---|---|
| **PLAT-OTEL-1** | OpenTelemetry 全链路运行验收：Console/API → Trigger → Orchestrator → Kafka → Worker → Report | [`../backlog/platform-capability-evolution-backlog-2026-09-26.md`](../backlog/platform-capability-evolution-backlog-2026-09-26.md) | 🟡 运行证据采集脚本和验收步骤已补；真实链路、Tempo/Loki 关联和告警触发仍需 staging 证据 |
| **PLAT-BP-1** | 背压与容量大盘收口：admission、claim/report、Outbox、Kafka lag、Hikari、PG 锁等待、Worker lease | 同上 | 🟡 容量仪表盘、Prometheus 告警和证据脚本已补；真实压测阈值和容量结论仍需 staging |
| **PLAT-WR-1** | 五类 Worker Runtime / SPI 行为一致性复核 | 同上 | ✅ 核心实现和本地 Sim 已完成；🔒 staging/生产级证据仍需补齐 |
| **PLAT-KEDA-1** | KEDA staging 验证：dynamic sharding + backlog / lag 扩缩 + drain | 同上 | P1；需要真实 K8s + KEDA operator |
| **PLAT-GITOPS-1** | GitOps staging 接入：镜像、ops repo、Argo CD、Helm values、smoke | 同上 | P1；当前只有骨架 |
| **PLAT-CDC-1** | CDC / Streaming 方案设计 | 同上 | P2；业务触发后再立项 |

### G4. Console AI 助手与成本治理 · P1/P2 · 🟡 基线已落地，治理缺口待实施

权威方案：[`ai-assistant-contextual-experience-and-cost-governance-2026-09-29.md`](../plans/ai-assistant-contextual-experience-and-cost-governance-2026-09-29.md)。本节只登记仍可能实施的后端工作；AI 方案中的暂缓和不做项单独列出，不能当作当前开发任务。

| ID | 主题 | 状态 |
|---|---|---|
| **AI-CTX-1** | 版本化页面上下文、会话语义、租户/角色授权、领域拒答和来源引用契约 | 🟡 已有认证、角色、租户校验、上下文脱敏、领域/安全拒答和来源引用；版本化页面上下文与服务端会话持久化未完成 |
| **AI-COST-1** | 输入/输出 token 上限、并发舱壁、日/月预算、provider 错误分类、成本与拒答指标 | 🟡 已有 RPM 限流、请求超时、有界并发、单次输出 token 上限、可选租户日请求预算和严格 Redis 故障策略；真实费用核算与月预算仍未实现 |
| **AI-AUDIT-1** | AI 审计默认只保留元数据/哈希/成本信息，复核原文预览留存策略和查询权限 | 🟡 已增加 V217 时间索引和默认关闭的可配置清理任务；原文预览是否保留、查询权限和生产保留期仍需合规确认 |

以下是已冻结的范围决策，不重新排成开发待办：

| 决策 | 范围 | 状态 |
|---|---|---|
| **AI-DEC-1** | Spring AI M3 → GA | 🟡 等上游发布，当前不做升级 |
| **AI-DEC-2** | 外部 AI provider 契约接入 CI | ❌ 不做；需要外部 secrets，保留人工/受控验证方案 |
| **AI-DEC-3** | AI 上线判定和受控试生产 | 🟡 暂缓，等待真实质量/成本证据 |
| **AI-DEC-4** | Phase 3 AI 直接写操作/HITL | 🟡 后置，继续复用现有审批闭环 |

### G5. Console 使用率统计 · P1/P2 · 🟡 基础摘要已有，日聚合方案未完成

权威方案：[`console-usage-statistics-plan-2026-09-29.md`](../plans/console-usage-statistics-plan-2026-09-29.md)；前端配套清单见配对仓库 `batch-console/docs/backlog/ai-and-usage-statistics-todo-2026-09-29.md`。第一版只做可解释的 PostgreSQL 日聚合，不引入 Kafka、ClickHouse 或通用行为分析平台。

| ID | 主题 | 状态 |
|---|---|---|
| **USAGE-1** | 固化指标目录、成功口径、数据来源优先级、权限和保留期 | ✅ 后端指标来源、成功口径、租户权限和派生数据边界已固化；长期保留期仍需运维策略确认 |
| **USAGE-2** | Flyway `console_usage_daily` 月分区、严格 RLS、索引和保留策略 | ✅ V216 已落地分区、严格 RLS 和索引；长期分区保留/归档仍需 staging 策略 |
| **USAGE-3** | 后端事件标准化、有界批量 upsert、`usage-summary` DTO/API/OpenAPI | ✅ 已落地操作审计投影、并发累加 upsert 和独立 summary 契约；当前为同步 best-effort 投影，有界异步批量明确不在本轮范围 |
| **USAGE-4** | 与前端埋点、操作审计和业务终态对账，补齐租户隔离/并发/失败语义测试 | ⏳ 待真 PG 联测和前端展示对账；当前接口不能替代业务结果对账 |

### G6. Console 维护与服务降级完善 · P0/P1 · 🟡 基础能力已落地，增强项未完成

权威方案：[`../plans/maintenance-degradation-hardening-plan-2026-09-29.md`](../plans/maintenance-degradation-hardening-plan-2026-09-29.md)。本项只完善现有维护模式、下游降级、前后端状态契约和多副本收敛，不扩展为通用服务治理平台。

| ID | 主题 | 状态 |
|---|---|---|
| **MAINT-BE-1** | PostgreSQL 维护状态唯一事实源、版本 CAS、实例确认和重启恢复 | 🟡 V215 已落地；已实现启动读取、5 秒轮询、版本 CAS 和失联写保护，双实例/staging 证据待补 |
| **MAINT-BE-2** | 维护 503 body/header、权限绕过和审计契约收口 | ✅ 后端 503 body、`X-Maintenance`、`Retry-After`、版本 Header、管理员热切换和审计已收口；细粒度旁路权限与前端联测仍按后续边界治理 |
| **DEGRADE-BE-1** | `X-Degraded-Source` 标准化输出，读 fallback / 写 fail-fast 守护 | ✅ 后端 fallback 统一写来源 Header，写路径继续 fail-fast；前端消费和端到端联测待补 |
| **DEGRADE-BE-2** | 复用现有 Micrometer 增加维护状态、维护 503、fallback 比例和耗时告警 | ✅ 后端维护状态 gauge、维护请求结果、下游 fallback 计数、副本健康和容量/背压告警已补；真实阈值校准待 staging |
| **MAINT-JOINT-1** | 双 Console 实例维护切换、下游断路器和恢复联测 | 🔒 需多实例/staging 环境验证 |

明确不做：服务网格、通用动态路由、工单/通知中心、独立配置中心和写接口自动成功降级。

### G7. CI 外部 Actions 版本治理 · P2

专题记录：[`../backlog/ci-external-action-upgrade-backlog-2026-09-30.md`](../backlog/ci-external-action-upgrade-backlog-2026-09-30.md)。责任范围：仓库 CI/发布流程维护者；截至 2026-09-30，第一批 Actions/扫描工具已更新，Gitleaks Linux artifact 基础正负样例已验证，仍需 PR/Full Gate 实跑收口。

| ID | 主题 | 状态 |
|---|---|---|
| **CI-ACTION-1** | checkout、setup-python/java/node/go、upload-artifact 与 Hadolint Action 升级 | 🟡 已统一升级；待 PR/Full Gate 验证 runner、缓存和 artifact 契约 |
| **CI-ACTION-2** | Docker Buildx setup action v3→v4 | 🟡 已升级到 v4；待镜像构建和 Docker Bake CI 验证 |
| **CI-ACTION-3** | 其余第三方 Action、扫描器和浮动版本引用复核 | 🟡 已完成盘点并保留兼容引用；GitHub Actions Dependabot 已开启每周版本队列，`trivy-action`、Checkov、发布 Action 等仍按上游安全公告和契约单独复核 |
| **CI-SEC-1** | Gitleaks 8.30.1 CI Linux artifact 正向/负向检测验证 | 🟡 Docker linux/amd64 已验证：合成 `ghp_...` 正向退出 1、负向退出 0；仍需 PR/Full Gate 实跑，且该样例不代表所有规则 |
| **CI-SEC-2** | 更新 Squawk CLI 与 oasdiff 固定版本 | 🟡 已升级到 Squawk `2.65.0` / oasdiff `1.32.1`；当前 OpenAPI spec 对比已本地通过，本批无迁移文件因此 Squawk 安全检查按规则跳过；仍待 PR/Full Gate 实跑 |

### H. 合规收尾 · P3

| ID | 主题 | 来源 | 状态 |
|---|---|---|---|
| ~~**LIC-1**~~ | NOTICE 文件 copyright + 上游聚合完整化 | license-risk-assessment:171 | ✅ 2026-05-01 NOTICE 升级：从 16 行指针式扩到 ~110 行，聚合 Spring Boot/POI/Kafka/Flyway/MyBatis/MinIO/Logback/SLF4J 等 20+ 主要上游 attribution（满足 Apache-2.0 §4(d)）|
| **LIC-2** | SBOM 与第三方清单嵌入 artifact | license-risk-assessment:173 | 🟡 **暂缓** — 🔒 部分 ops(本地能改 maven,CI 注入 + artifact 校验需 ops);触发条件:合规审计 / 客户 SBOM 要求时启动 |
| ~~**LIC-3**~~ | 新依赖 license 检查与约束 | license-risk-assessment:174 | ✅ 2026-05-01 `scripts/ci/check-dependency-licenses.sh` 落地 |

### I. Worker 灰度升级 runbook 验证 · P2

> 🔒 完整端到端验证需 staging worker 集群，本地仅能补 IT 模拟 — 见 §九

| ID | 主题 | 来源 |
|---|---|---|
| **WK-up-1** | drain 接口能否发起并查询 claimed-tasks | rolling-upgrade-workers:76 |
| **WK-up-2** | 超时后 Orchestrator 接管确认 | rolling-upgrade-workers:77 |
| **WK-up-3** | force-offline 紧急场景验证 | rolling-upgrade-workers:78 |

### J. @Deprecated forRemoval 物删积压 · P3

> 代码线索：grep `forRemoval=true`
> 🔒 DEP-1 需灰度门禁，DEP-3/4/5 已物删；DEP-2 本地可做 — 见 §九

| ID | 主题 | 代码位置 | 备注 |
|---|---|---|---|
| ~~**DEP-1**~~ | `HttpOrchestratorTriggerAdapter` 物删 | batch-trigger:HttpOrchestratorTriggerAdapter.java:27 | ✅ 已随 ADR-010 唯一异步路径落地完成；保留历史记录 |
| ~~**DEP-2**~~ | `BatchSecurityProperties.testingOpen` 物删 | batch-common:BatchSecurityProperties.java:45,51 | ✅ 已完成；代码与配置键已物理删除，保留历史记录 |
| ~~**DEP-3**~~ | `ConsoleAlertRoutingExcelController` 4 处旧端点物删 | batch-console-api | ✅ 2026-05-01 物删 + OpenAPI 同步 |
| ~~**DEP-4**~~ | `ConsoleFileTemplateExcelController` 4 处旧端点物删 | batch-console-api | ✅ 同上 |
| ~~**DEP-5**~~ | `ConsoleResourceQueueExcelController` 4 处旧端点物删 | batch-console-api | ✅ 同上 |

### K. 代码内 follow-up · P3 · ✅ 已完成

| ID | 主题 | 代码位置 |
|---|---|---|
| ~~**EXT-1**~~ | V5-P2-4-ext: JOB / BATCH compensation happy-path | ✅ `DefaultCompensationServiceTest` 已覆盖 JOB/BATCH submit happy-path（约 269-328 行）；原 TODO 索引已过期 |

### L. 历史一次性失败修复 · P3 · ✅

`HIST-1` 4 个 E2E ConditionTimeout 失败已修（2026-05-01 的历史记录；当时结论见已归档的 `docs/archive/testing/e2e-coverage-2026-05-03.md`，不代表当前 E2E 状态）。

### M. IPv6 Happy Eyeballs 渐进治理 · P1

权威方案：[`../plans/ipv6-happy-eyeballs-rollout-2026-09.md`](../plans/ipv6-happy-eyeballs-rollout-2026-09.md)。本事项采用 transport 抽象和应用级适配器，不做全工程直接替换。

| ID | 主题 | 状态 |
|---|---|---|
| **HE-1 ~ HE-4** | 平台 transport、JDK 外部调用迁移、全地址 SSRF、既有 OkHttp DNS 对齐 | ✅ 已完成 |
| **HE-5** | IPv4/IPv6 黑洞与单栈/双栈故障注入 | ✅ 本地已完成，staging 证据归 HE-7 |
| **HE-6** | 五语言 SDK 控制面 HTTP 双栈治理 | ✅ 本地已完成；生产同构网络全矩阵归 HE-7 |
| **HE-7** | staging DNS/路由/NetworkPolicy/出口代理验证 | 🔒 外部阻塞 |

---

## 四、🟡 半成

### V6-P2-EXCEL-GODCLASS 6/7 完成

- 进度：6 个 god class 拆完平均 -67% LOC，1 个保留
- 保留项：`ConfigPackageExcelValidator` 874 LOC — 已是 single-purpose validator，内部 8 个 `validateXxxRows` 共享 cross-reference 数据，split 反而 fragment + overhead → 评估为"不拆"
- 来源：hardening-backlog v6
- 下一步：无（评估为完整状态，7 类标 6/7 是因第 7 类决定不动）

### V5-P1-1 ADR-009 Workflow DSL · 代码 100%，业务配置 0%

- 代码：Stage 1（V72）/ 1.2（worker outputs）/ 2（`WorkflowParamResolver` 160 LOC + 10 单测）/ 3（`mergeNodeParams` 集成）全栈
- 业务：Stage 4（7 workflow 配 DSL）deferred，等业务方触发
- 来源：hardening-backlog v6 / project-assessment v1

### ADR-010 Stage 6 灰度 / Stage 7 物删

- 状态：文档 + 代码 ✅，operational 0%
- 未做：见 §三-D
- 🔒 全部 7 项需 ops / staging / prod — 见 §九

### Worker 4 模块单测密度（V6-D-5）✅

- 已完成：4 个 `Default*StageExecutor` 均已有 5 个以上场景；Import / Export / Process / Dispatch 的 `*StepExecutionAdapterTest` 均补齐 5 个核心契约场景。
- 来源：hardening-backlog v6

### 历史 flaky 测试根治 ✅

- `JobLaunchToFinishLifecycleIntegrationTest`：失败上报补齐 claim 生成的 `partitionInvocationId`，恢复 CAS 真实契约后取消禁用。
- `RlsStrictModePreflightIntegrationTest`：回滚策略对齐生产 SQL 的空字符串 GUC 语义后取消禁用。
- 全仓仅保留 `ShellTaskExecutorTest` 的 Windows 平台条件禁用，不再存在无条件 `@Disabled`。
- 2026-09-11 连续 3 轮目标集成验证通过。

---

## 五、✅ 已完成（简表）

> 维护：每月 review，把 ✅ 项移到 §归档（避免本节越写越长）

### deep-issue §5（全 6 项 ✅）

- §5.1 trigger Spring Security：`cd389a0b`（2026-04-22）— `TriggerSecurityConfiguration:42-46` 真起 SecurityFilterChain
- §5.2 X-Console-Token：✅ commit `ff20c36f` 主代码 + yaml + OpenAPI + 测试 9 文件 +20/-168 物理删除；grep 全仓 0 残留
- §5.5 / §5.6 / §5.10 idempotency 三层边界：ADR-011 定稿，3 层代码已实施
- §5.7 trigger → orchestrator 异步：ADR-010 全栈 7 stage（`9587b8bf` / `087f6b7a` / `1ca3a957` / `22b330ea` / `788b637d` / `68bc49e8`），22 测试全部通过
- §5.11 webhook durability：V81 + `WebhookDeliveryRelay` 278 行 + 7 单测全部通过
- §5.12 Console Job god-class：`DefaultConsoleJobApplicationService` 现 90 LOC + 6 兄弟类 1278 LOC

### ADR 路线图（5 完成 / 2 deferred / 2 暂缓）

- ✅ **ADR-009** Workflow DSL Stage 1 / 1.2 / 2 / 3 全栈
- ✅ **ADR-010** trigger 异步 Stage 1-5 代码 100%
- ✅ **ADR-011** idempotency boundary 定稿
- ✅ **ADR-012** 失败分类（V111）
- ✅ **ADR-021** 数据对账 v1.0（V118 + DataQualityCheckExecutor + EFFECTIVE gate）
- ✅ **ADR-022** Forensic v0.1（V116 + 同步 bundle + SHA-256）
- ✅ **ADR-023** 多日历联动（V112-V114）
- ✅ **ADR-025** Workflow 静态校验（15 条规则 V1-V15）
- ✅ **ADR-026** dry-run 全链路（V115 + V117 + DryRunGuard SPI + L1/L2/L3 service）
- 🟡 ADR-009 Stage 4 deferred / ADR-010 Stage 6+7 灰度+物删 deferred
- 🟡 ADR-024 / ADR-027 暂缓（触发条件未达）

### v2 评估 4 项硬化（全 ✅）

- V6-OPS-1 Kafka topics（含 `scripts/ci/validate-kafka-topics.sh` 守护，三向 diff）
- V6-OPS-2 Prometheus 3 告警：`0c623eb0`
- V6-Q-1 9 处 FQN 违规：`8dc6eac1`
- V6-NOISE-1 运行日志噪声治理：`aa249bf8` / `0d650fab`

### v6 P2 god-class 拆分（全 ✅）

- V6-P2-WEBHOOK-DURABILITY：`b74e0a0c`
- V6-P2-ORCHESTRATOR-GODCLASS：`7d6faad6`（`DefaultTaskOutcomeService` 926→795 / `DefaultWorkflowNodeDispatchService` 840→371）
- V6-P2-EXCEL-GODCLASS：6/7 拆完平均 -67% LOC（`002b8864` + `bd0f0532` + `b9eefb47`）
- V6-P2-CONSOLE-IDEMPOTENCY：3 层代码各自归位
- V6-P2-POSITIONAL-ARGS v4：闭环 + 守护测试到位

### Query Record 工厂（QF-1 / QF-2 / QF-3 ✅）

12+ Query record 静态工厂补齐 + `QueryRecordConstructionConventionTest` 守护到位 + ~25 处 `new XxxQuery(t, null, null, null, ...)` call site 替换完成。

### v5 历史 P0-P3（19 项 ✅）

详见 [`hardening-backlog.md`](./hardening-backlog.md) §四（已完成 v5 清账表）。

---

## 六、❌ 不做（归档）

| ID | 主题 | 理由 |
|---|---|---|
| V5-P2-1 | 6 渠道非 SFTP dispatch 单 adapter IT | 业务接入对应渠道时再做，无前置 |
| V5-P2-9 | Workflow PIPELINE/MIXED + GATEWAY/FILE_STEP 节点 | 依赖 V5-P1-1 完整落地后再做（现 ADR-009 Stage 4 也 deferred）|
| V5-NEW-1 | workflow steps 协议错位 | 不构成 bug — worker 代码不读 task_payload.steps，4-24 commit `3dbb6d22` 修了 resolveJobCode + node_params 后未复现 |
| V5-NEW-2 | exp_settlement_csv_v1 模板源头 | default-tenant 7 个 "system" 模板之一，业务无引用，不影响主链路 — 历史遗留 + 不影响，不再追溯 |

---

## 七、维护规则

1. **新发现待办**：加进对应主题段（A-L），用编号 `<主题缩写>-<序号>`
2. **状态变更**：
   - ⏳ → 🟡：开干时改
   - 🟡 → ✅：验收门禁通过（单测 / IT / 灰度后）
   - ✅：本节保留 1 个月，然后归档到 hardening-backlog
3. **每月 review**：用 grep + 真实代码状态校验每条，避免"顶部已完成 / 明细未更新 / 引用悬空"的不一致
4. **删除 / 不做**：进 §六，带理由
5. **源文档变更同步**：hardening-backlog / deep-issue / project-assessment 状态变更时，同步本文件；反向不做（本文件是聚合视图，不取代源文档）

---

## 八、对账校验

| 校验项 | 命令 | 说明 |
|---|---|---|
| Query record 工厂数 | `for f in $(find docs/query/*.java); do grep -c 'public static.*\bof' $f; done` | 已闭环，详见 §五 Query Record 工厂段 |
| 代码 TODO 总数 | `rg '\b(TODO\|FIXME\|XXX\|HACK)\b' --glob '*.java'` | 当前应重新扫描；旧的 `DefaultCompensationServiceTest:167` V5-P2-4-ext 已由现有 happy-path 测试覆盖 |
| `@Deprecated forRemoval=true` | `rg 'forRemoval' --glob '*.java'` | 5 类（HttpOrchestratorTriggerAdapter / BatchSecurityProperties / 3 个 ExcelController），后 3 个已物删 |
| 多 null 占位 inline new（≥2 null）| `rg -nU --multiline 'new \w+\([^)]*null[^)]*null[^)]*\)' --glob '*.java'` | POSITIONAL-ARGS v4 闭环后回归 0；新增由守护测试拦截 |

---

## 九、🔒 本地不能做（需 ops / staging / DBA / 业务方 配合）

> 用途：本仓库内的自动化 agent / 开发者**无法独立完成**的项，挂在这里直至外部条件就绪。
> 图例：每条标注阻塞类型 — `[ops]` 部署/CD · `[staging]` 预发环境 · `[prod]` 生产环境 · `[DBA]` 数据库变更评审 · `[BIZ]` 业务方决策 · `[client]` 外部 API 客户端确认

| ID | 主题 | 阻塞类型 | 卡在哪 |
|---|---|---|---|
| **ADR10-S6/S7** | 异步路径灰度与同步入口清理 | — | 已完成；历史灰度步骤不再适用 |
| **WK-up-1** | drain 接口能否发起并查询 claimed-tasks（完整验证）| `[staging]` | 本地可补 IT 模拟，完整端到端验证需 staging worker 集群 |
| **WK-up-2** | 超时后 Orchestrator 接管确认（完整验证）| `[staging]` | 同上 |
| **WK-up-3** | force-offline 紧急场景验证（完整验证）| `[staging]` | 同上 |
| **LIC-2** | SBOM 嵌入 artifact + 第三方清单 | `[ops]` 部分 | 本地能改 maven 配置，但 CI 注入 + artifact 校验需 ops |

本节只维护逐项状态，不维护易随去重口径变化的汇总数字。FE-1/2/3、CC-1/2 已在 §二标记完成，不再列入待办。
