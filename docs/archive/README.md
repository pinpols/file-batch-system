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
| `design/` | mega 设计文档历史快照 |
| `testing/` | 历史测试报告 + 测试 plan 旧版 + 跑测产物 |

## 当前权威 vs 归档对照

| 主题 | 当前权威 | 归档历史 |
|---|---|---|
| 系统流程 | `architecture/system-flow-overview.md` | — |
| 设计说明 | `design/` 14 个章节文件 + `design/README.md` 索引 | `archive/design/system-design-2026-03-21.md`（**存根**：20 章+95 子节标题供锚点兼容，正文移到主干 / git 历史）+ [`archive/design/mega-chapter-map.md`](design/mega-chapter-map.md)（mega 20 章 → 当前位置导航） |
| 扩展性评估 | `architecture/scalability-assessment.md`（2026-04-25）| `archive/architecture/scalability-ha-assessment-2026-03-26.md`（初版 + 6 项改造完成快照）|
| 深度分析 | `analysis/deep-issue-analysis.md`（2026-04-23 最新）| `archive/analysis/deep-issue-analysis-v1.md`/`-v2`/`-v3`（4-15 / 4-17 / 4-20）|
| 修复报告 | `analysis/fix-report.md`（2026-04-22 最新）| `archive/analysis/fix-report-v1`/`-v2`/`-v3` |
| 测试计划 | `testing/full-project-test-plan.md` | `archive/testing/test-plan-2026-03-28.md` |
| 测试报告 | `testing/load-test-report.md` | `archive/testing/historical-test-reports-2026-04-09-10.md` |
