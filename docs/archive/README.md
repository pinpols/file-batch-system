# 归档目录（archive/）— 历史快照，不再维护

> **不要**改这里的文件。当前权威版本在 `docs/{analysis,architecture,design,runbook,testing}/` 主干目录。

## 命名约定

| 形态 | 用途 | 例 |
|---|---|---|
| `<topic>-YYYY-MM-DD.md` | 单点快照（按 git 首次提交日期） | `system-deep-analysis-2026-04-09.md` |
| `<topic>-YYYY-MM-DD-DD.md` | 跨天合并快照（起讫日） | `historical-test-reports-2026-04-09-10.md` |
| `<topic>-vN.md` | 多版本归档（v 表征版本，不加日期） | `deep-issue-analysis-v1.md` ~ `-v3.md` |

## 子目录分类

| 目录 | 收纳 |
|---|---|
| `analysis/` | 分析报告 / bug 路线图 / engineering backlog / 深度分析多版本 |
| `architecture/` | 架构 audit / 命名重构候选 / 历史 scalability 评估 / design gap |
| `backlog/` | 已实现 / 已替代 / 已裁定暂不继续滚动维护的 backlog 与施工方案 |
| `design/` | mega 设计文档历史快照 |
| `review/` | 历史代码审查快照 |
| `runbook/` | 已废弃或已被新 SOP 替代的运维流程 |
| `testing/` | 历史测试报告 + 测试 plan 旧版 + 跑测产物 |

## 当前权威 vs 归档对照

| 主题 | 当前权威 | 归档历史 |
|---|---|---|
| 系统流程 | `architecture/system-flow-overview.md` | — |
| 设计说明 | `design/` 14 个章节文件 + `design/README.md` 索引 | `archive/design/system-design-2026-03-21.md`（**存根**：20 章+95 子节标题供锚点兼容，正文移到主干 / git 历史）+ [`archive/design/mega-chapter-map.md`](design/mega-chapter-map.md)（mega 20 章 → 当前位置导航） |
| 扩展性评估 | `architecture/scalability-assessment.md`（2026-04-25）| `archive/architecture/scalability-ha-assessment-2026-03-26.md`（初版 + 6 项改造完成快照）|
| 深度分析 | `analysis/deep-issue-analysis.md`（2026-04-23 最新）| `archive/analysis/deep-issue-analysis-v1.md`/`-v2`/`-v3`（4-15 / 4-17 / 4-20）|
| 修复报告 | `analysis/fix-report.md`（2026-04-22 最新）| `archive/analysis/fix-report-v1`/`-v2`/`-v3` |
| 项目评估 | `analysis/project-assessment.md`（2026-04-30，已去日期）| `archive/analysis/project-assessment-2026-04-29.md`（v1 历史基线）|
| ADR 12/21..27 范围决策 | CLAUDE.md "ADR 实施范围纪律" 章（权威）| `archive/analysis/adr-012-021-027-priority-scope-2026-05-06.md`（原 fold 来源）|
| PG schema 多租隔离决策 | CLAUDE.md "多租隔离" 章（权威）+ V82-V85 落地 | `archive/analysis/pg-schema-audit-2026-05-03.md` |
| 越界审计 | `analysis/system-scope-boundary.md`（架构基准）| `archive/analysis/system-scope-boundary-audit-2026-05-06.md`（一次性代码审计快照）|
| 时区一致性 | CLAUDE.md "时区策略" 章（权威）| `archive/analysis/batch-system-timezone-consistency-check-2026-05-05.md` |
| 业界对标 benchmark | `architecture/scalability-assessment.md` 等吸收结论 | `archive/analysis/{orchestrator,worker,file-io,batch-day-instantiation}-vs-industry-2026-05-03.md` |
| Sonar 清理 | git commit history（2026-05-02 已闭环）| `archive/analysis/sonar-cleanup-2026-05-02.md` |
| 持久层与测试架构 | ADR-001 + `design/` 持久层章节 | `archive/analysis/persistence-and-test-architecture-2026-05-02.md` |
| Worker ADR backlog 优先级 | CLAUDE.md "ADR 实施范围纪律" + `architecture/adr/` 各 ADR 文档 | `archive/analysis/worker-adr-backlog-priority-2026-05-03.md`（时效性 priority 表）|
| 测试计划 | `testing/full-project-test-plan.md` | `archive/testing/test-plan-2026-03-28.md` |
| 测试报告 | `testing/load-test-report.md` | `archive/testing/historical-test-reports-2026-04-09-10.md` |
| 发布流程 | `runbook/releasing.md` | `archive/runbook/release-process-2026-05-22.md`（已废弃 GitOps 蓝图） |
| 代码审查 | `review/` 当前保留近期仍有参考价值的快照 | `archive/review/` 历史快照 |

## 2026-08-21 归档批次

本批只移动以下三类文件，不修改原文内容：

1. `analysis/` 下已 fold 到滚动文档或只保留历史证据的一次性 audit / review / 升级评估。
2. `backlog/` 下已实现、已替代，或由当前容量/验证文档接管的施工方案。
3. `runbook/` 下文件名和正文均已标明废弃的历史发布流程。
