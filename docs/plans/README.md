# 工作台账与阶段计划

[当前待办](todo-master.md) 和 [长期硬化](hardening-backlog.md) 是持续台账；下列方案、矩阵与执行日志属于各自阶段。台账维护原文，阶段文件保留原基线日期。

近期工程化计划与能力对照：

| 文档 | 用途 |
|---|---|
| [当前专用工作树工程治理台账](development-worktree-governance-2026-10-24.md) | 用户 19 项范围、可读性集中整改、静态规则证据和未关闭项；不编译、不运行应用测试 |
| [Workflow 与每日连续性隔离整合计划](./workflow-daily-integration-plan-2026-10-14.md) | ADR-048 / 契约 2026-10-14.2；全新数据库、单一运行模型、既有批调度语义与跨域影响迁移、I0–I6 门槛；实现尚未验收 |
| [数据完整性、索引与分区治理整改方案](database-integrity-index-partition-remediation-2026-10-18.md) | P0/P1优先；全新数据库、外键与应用守护逐关系对齐、归档/清理、索引及分区联合验收；沿用REF/IDX/PART任务，不重复计数 |
| [数据库引用完整性逐表核对与整改方案](database-reference-integrity-remediation-2026-10-18.md) | 2026-10-18实际V291目录；176逻辑表/107分区、现有FK与替代校验、应补/不加理由、REF-01–08整改及验收 |
| [数据库索引逐表审查与整改方案](database-index-review-remediation-2026-10-18.md) | 176表/589逻辑索引；结构、查询、FK配套、冷热/队列、重叠候选与IDX-01–08；只读日期裁剪计划及性能边界 |
| [数据库表分区与分区扩展专项方案](database-partition-lifecycle-remediation-2026-10-18.md) | 10父/107子；RLS/授权、持续扩建、在途淘汰、多库/时区/DEFAULT、DDL锁、统计、监控及PART-01–12 |
| [Workflow 与每日连续性整合验收总览](workflow-daily-integration-validation-2026-10-14.md) | 当前焦点、根因批次、本地/外部边界、验证层级与最终放行条件；R 项状态以独立验收矩阵为准 |
| [Workflow 与每日连续性 R01–R36 验收矩阵](workflow-daily-integration-acceptance-matrix-2026-10-14.md) | 逐项当前状态、证据范围、关闭条件和场景断言；状态唯一来源 |
| [Workflow 与每日连续性整合执行证据日志](workflow-daily-integration-execution-log-2026-10-14.md) | 按批次保存命令、环境、首红/修复、局部验证结果和历史状态快照；不替代当前矩阵状态 |
| [Workflow 统一运行与 Pipeline 来源方案](./workflow-unified-execution-and-system-pipeline-plan-2026-10-14.md) | 保留来源分支阶段记录；当前实施范围已由 ADR-048 与整合计划取代 |
| [Console 产品用户旅程改造方案](./console-product-journey-improvement-plan-2026-10-10.md) | 以四类 RBAC 角色和批量业务旅程为轴，规划安全默认、高风险操作反馈、日常运维、文件/账期对账与四角色端到端验收；保留专业场景能力 |
| [租户作业配置与资源边界收敛计划](./tenant-job-configuration-simplification-plan-2026-10-09.md) | 精简 41 列文件模板和作业配置入口；将队列容量、配额与 Worker 路由收归平台，定义默认值、兼容迁移及验收门槛 |
| [静态分析工程化治理实施计划](./static-analysis-governance-plan-2026-10-12.md) | 集中 Sonar/CodeQL/SpotBugs/PMD/IDE 检查边界、suppressions 守护、全量扫描证据和存量复核阶段 |
| [每日连续性来源方案](./daily-job-continuity-closure-2026-10-14.md) | 日切、依赖、输入预期的来源记录；按整合计划统一 Workflow 归属及新库部署 |
| [Console OIDC SSO 接入方案](./console-oidc-sso-integration-plan-2026-10-09.md) | 企业身份提供方 OIDC 登录边界、账号映射与验收；后端最小闭环及前端 pilot 入口已实现，真实 IdP 试点联测和生产启用仍待完成 |
| [java-design-expression-improvement-plan-2026-10-08.md](./java-design-expression-improvement-plan-2026-10-08.md) | 注释归属、测试装配、固定载荷、任务结果不变量与协作者内聚的后续改进方案；不重复已有可读性/契约治理 |
| [typed-contract-enum-constant-governance-plan-2026-10-07.md](./typed-contract-enum-constant-governance-plan-2026-10-07.md) | 固定 Map/Object 契约、有限域裸字符串、重复常量与精准增量门禁的分批治理计划 |
| [java-readability-refactoring-roadmap-2026-08-12.md](./java-readability-refactoring-roadmap-2026-08-12.md) | Java 可读性、事务自注入、固定 Map 契约和复杂类的分阶段治理路线 |
| [backend-borrowings-and-improvements-2026-07-10.md](backend-borrowings-and-improvements-2026-07-10.md) | 后端工程化借鉴、已落地能力与剩余治理项 |
| [bfs-open-source-scheduler-boundary-roadmap-2026-06-29.md](./bfs-open-source-scheduler-boundary-roadmap-2026-06-29.md) | 对标开源调度器后的 BFS 调度 / 编排边界、五块强化计划和验证口径 |
| [spring-boot-engineering-patterns-plan-2026-08-02.md](./spring-boot-engineering-patterns-plan-2026-08-02.md) | Spring Boot 工程化样板落地计划 |
| [engineering-benchmark-comparison-2026-08-02.md](./engineering-benchmark-comparison-2026-08-02.md) | BFS 与优秀系统的工程能力对照表 |
| [AI 助手全局入口与领域/成本治理](./ai-assistant-contextual-experience-and-cost-governance-2026-09-29.md) | Console AI 全局入口、拒绝通用问答、配额/会话/审计与数据治理改造计划 |
| 配套前端实施方案 | `batch-console` 仓库：`docs/engineering/ai-assistant-frontend-implementation-plan.md`，覆盖全局入口、页面上下文、会话 UI 与前端验收 |
| [Console 使用率统计方案](./console-usage-statistics-plan-2026-09-29.md) | PostgreSQL 日聚合、业务成功率口径、租户隔离和使用率查询契约 |
| [Console 维护与服务降级完善方案](./maintenance-degradation-hardening-plan-2026-09-29.md) | 维护窗口、下游降级、前后端契约、多副本一致性和联测门禁 |
| [控制面治理实施记录](./control-plane-governance-implementation-2026-09-30.md) | 多副本维护一致性、使用率日聚合、OTel 证据、容量观测、Worker 滚动升级和 AI 成本/审计治理的落地范围与验收记录 |

## 计划登记

计划状态以各文档头部和 [`../analysis/todo-master.md`](todo-master.md) 为准；“Implemented”文档只保留实施与验收依据。

| 领域 | 文档 |
|---|---|
| 控制面与 HA | [控制面吞吐](./control-plane-throughput-optimization-2026-09-07.md)、[HA 部署](ha-deployment-plan-2026-06-13.md)、[多租隔离](./multi-tenant-isolation-plan-2026-05-31.md)、[biz 分层租户](./biz-tiered-tenancy-plan-2026-06-14.md) |
| 文件与恢复 | [Export keyset](export-partition-keyset-range-2026-06-06.md)、[Export slice](export-partition-slice-2026-06-06.md)、[S3 SDK 迁移](migrate-minio-sdk-to-aws-sdk-v2-2026-06-06.md)、[完整性 sidecar](./file-integrity-sidecar-manifest-plan-2026-06-07.md)、[checkpoint](checkpoint-resume-design-2026-07-12.md) |
| 批量日与结算 | [整批量日 dry-run](./batch-day-dry-run-enhancement-plan-2026-09-08.md)、[结算差距治理](./settlement-gap-remediation-roadmap-2026-06-20.md) |
| SDK 与前端 | [SDK roadmap](sdk-h2-roadmap-2026-05-31.md)、[roadmap 进度](sdk-h2-roadmap-progress-2026-05-31.md)、[FE 工作清单](fe-atomic-sdk-h2-worklist-2026-06-01.md) |
| 可观测与扩展 | [Alertmanager 迁移](alertmanager-migration-plan-2026-07-12.md)、[AI 接入](ai-integration-plan-2026-07-10.md)、[AI 助手体验与成本治理](./ai-assistant-contextual-experience-and-cost-governance-2026-09-29.md) |
| 网络与外部 HTTP | [IPv6 Happy Eyeballs 渐进落地](ipv6-happy-eyeballs-rollout-2026-09-24.md) |

历史验证基础设施计划：

## r3 Validation Infrastructure Plans

补 `scripts/sim/` 之外的验证维度。详见 `docs/architecture/` 关于本系统验证分层的总图。

## 4 个 Plan

| # | Plan | 解决什么 | 优先级 | 估时 |
|---|---|---|---|---|
| 1 | [Chaos / Toxiproxy IT](r3-1-chaos-toxiproxy-it-2026-05-29.md) | broker / db 故障下的熔断 / 降级 / 自愈 | P0 | 1d |
| 2 | [运维剧本](r3-2-runbook-playbooks-2026-05-29.md) | on-call 凌晨 3 点的"怎么救" | P0 | 0.5d |
| 3 | [Soak Test](r3-3-soak-tests-2026-05-29.md) | 长期稳定性(泄漏 / 跨日 / 累计 lag) | P1 | 1d |
| 4 | [Forensic 回放](r3-4-forensic-replay-2026-05-29.md) | 用昨日生产数据本地回放看是否改判 | P1 | 1.5d |

## 并行度

- Plan #1 与 Plan #2 配对:剧本写"怎么救",IT 验"救得回" — 同一人接收益最大
- Plan #3 独立,可并行
- Plan #4 依赖 sim(已存在)+ console-api,可并行

## 总工期
完整 4 件套约 4 天工。可由 1 人顺序 / 多人并行。

## 总验收
4 个 Plan 各自验收完毕 + 在 `docs/architecture/` 加一节"验证维度全图",标注 sim / chaos IT / soak / forensic replay 各自负责的格子。

## 依赖治理阶段方案

| 文档 | 职责 |
|---|---|
| [bounded-context-migration-plan-2026-08-03.md](bounded-context-migration-plan-2026-08-03.md) | Bounded Context 依赖治理计划（2026-08-03） |
| [p1a-stage1-migration-plan-2026-05-30.md](p1a-stage1-migration-plan-2026-05-30.md) | P1-A Stage 1:console-api 子包归一迁移规划(2026-05-30) |

## 当前工作台账与阶段方案

| 文档 | 职责 |
|---|---|
| [README.md](README.md) | 阶段计划 |
| [hardening-backlog.md](hardening-backlog.md) | 硬化与遗留问题 Backlog |
| [platform-capability-evolution-plan-2026-09-26.md](platform-capability-evolution-plan-2026-09-26.md) | 批量平台能力演进待办（2026-09-26） |
| [pre-production-capacity-optimization-plan-2026-06-08.md](pre-production-capacity-optimization-plan-2026-06-08.md) | 上线前容量压测与优化计划 - 2026-06-08 |
