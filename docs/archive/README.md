# 归档目录（archive/）— 历史快照，不再维护

> 历史结论冻结，不在归档内继续维护当前待办。目录整理时可修复路径和索引，当前入口见下表。

## 命名约定

| 形态 | 用途 | 例 |
|---|---|---|
| `<topic>-YYYY-MM-DD.md` | 单点快照（优先采用正文的评估或执行日期；缺失时才用首次提交日期） | `system-deep-analysis-2026-04-09.md` |
| `<topic>-YYYY-MM-DD-to-YYYY-MM-DD.md` | 跨天合并快照（完整起讫日） | `historical-test-reports-2026-04-09-to-2026-04-10.md` |
| `<topic>-vN-YYYY-MM-DD.md` | 多版本快照保留版本号与原始日期 | `deep-issue-analysis-v1-2026-04-15.md` |

## 子目录分类

| 目录 | 收纳 |
|---|---|
| `analysis/` | 分析报告 / bug 路线图 / engineering backlog / 深度分析多版本 |
| `architecture/` | 架构 audit / 命名重构候选 / 历史 scalability 评估 / design gap |
| `backlog/` | 已实现 / 已替代 / 已裁定暂不继续滚动维护的 backlog 与施工方案 |
| `design/` | 已被现行文档承接的设计稿、mega 文档和早期模块/POM 草案 |
| `review/` | 历史代码审查快照 |
| `runbook/` | 已废弃或已被新 SOP 替代的运维流程 |
| `testing/` | 历史测试报告 + 测试 plan 旧版 + 跑测产物 + 测试治理 POC / 优化记录 |

## 当前入口与历史证据

| 主题 | 当前入口 | 归档材料 |
|---|---|---|
| 架构与范围 | [当前架构](../architecture/architecture-truth.md)、[工程成熟度路线](../architecture/engineering-maturity-roadmap.md)、[系统边界](../architecture/system-scope-boundary.md) | `architecture/` 的成熟度、容量、工作量和旧路线评估；撤销方案不再作为实施依据 |
| 当前问题 | [TODO Master](../plans/todo-master.md)、[硬化事项](../plans/hardening-backlog.md) | `analysis/` 的历史分析、修复记录和工程评估；归档状态不并入当前待办统计 |
| 设计 | [设计索引](../design/README.md)、[mega 章节映射](../design/legacy-design-chapter-map.md) | `design/` 的早期 mega 设计和模块/POM 草案；当前字段映射设计见 [现行说明](../design/import-export-column-mapping-default-inference.md) |
| 数据库与 SDK | [Schema guide](../design/database-schema-guide.md)、[SDK 契约](../sdk/README.md) | `analysis/` 的 SQL/DBA 审计和 SDK conformance、行业对标快照 |
| 测试与容量 | [测试策略](../testing/README.md)、[容量治理](../runbook/production-capacity-governance.md)、[验证记录](../verifications/README.md) | `testing/` 的旧覆盖矩阵、运行快照及 POC；2026-04-25 压测和 Worker Stage 结果在 `verifications/` 按日期留存 |
| CI 与部署 | [CI 手册](../runbook/ci.md)、[发布流程](../runbook/releasing.md)、[GitOps 接入](../runbook/gitops-onboarding.md) | `runbook/` 的旧 CI/CD 待办、CI 优化决策和失效流程；GitOps 接入手册持续维护，未归档 |
| 审核方法与结果 | [上线审计检查清单](../standards/go-live-audit-checklist.md)、[日期化审计](../audit/README.md) | `review/` 保存已被新一轮评审替代的历史结论；检查清单与报告各有入口 |
| 代码规模 | [当前精简 LOC](../stats/loc-current-lean.md) | 规模快照统一由 [stats/archive](../stats/archive/README.md) 管理，保留统计口径和原日期 |

日期表示材料的评估、执行或基线日期，不随目录整理日更新。历史正文保留原结论；允许修复迁移造成的链接失效、补充历史属性和指向当前入口，修正不构成重新验收。

## 2026-08-21 归档批次

本批只移动以下三类文件，不修改原文内容：

1. `analysis/` 下已 fold 到滚动文档或只保留历史证据的一次性 audit / review / 升级评估。
2. `backlog/` 下已实现、已替代，或由当前容量/验证文档接管的施工方案。
3. `runbook/` 下文件名和正文均已标明废弃的历史发布流程。
