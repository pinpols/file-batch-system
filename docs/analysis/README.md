# 演进分析索引

本目录区分当前治理入口、长期方案和带日期的历史分析快照。当前待办以 [`todo-master.md`](./todo-master.md) 为准，工程硬化事项以 [`hardening-backlog.md`](./hardening-backlog.md) 为准；日期快照保留当时证据，不代表当前代码状态或待办。

## 文件清单（编号即推荐阅读顺序）

| # | 文件 | 作用 | 何时看 |
|---|---|---|---|
| 01 | [todo-master.md](./todo-master.md) | 当前待办唯一索引 | 查询当前未完成事项 |
| 02 | [hardening-backlog.md](./hardening-backlog.md) | 硬化事项及外部证据约束 | 规划后续硬化工作 |
| 03 | [deep-issue-analysis.md](./deep-issue-analysis.md) | 2026-04-30 深度分析历史快照 | 复盘当时的问题与判断，不作为当前问题清单 |
| 04 | [fix-report.md](./fix-report.md) | 2026-04-21 修复记录历史快照 | 追溯当时修复与本地验证，不作为当前状态依据 |
| 05 | [project-assessment.md](./project-assessment.md) | 项目工程深度评估快照 | 查看历史评估，当前待办以 todo-master 为准 |
| 06 | [system-scope-boundary.md](./system-scope-boundary.md) | 系统职责范围与边界守护 | 判定新功能是否越界、季度复核 |
| 07 | [java-readability-phase-0-classification-2026-08-12.md](./java-readability-phase-0-classification-2026-08-12.md) | Java 可读性治理历史分类；机器快照见 [inventory](./java-readability-inventory-2026-08-12.md) | 追溯分类依据与保留例外 |

## 专题分析与快照

以下文档提供特定时点或特定主题的证据，不替代当前待办与硬化 backlog：

| 主题 | 文档 |
|---|---|
| 构建与运行时 | [CI 门禁耗时优化](./ci-gate-runtime-optimization-2026-10-07.md)、[构建耗时](./build-time-optimization-2026-09-12.md)、[脚本兼容矩阵](./script-runtime-compatibility-matrix-2026-09-01.md)、[JDK 特性](./jdk-feature-usage-analysis-2026-06-09.md) |
| 数据与扩展 | [Schema 优化](./schema-optimization-review-2026-09-07.md)、[扩展状态](./scaling-state-and-biz-path-2026-06-14.md)、[单 Worker 容量](./single-worker-tens-of-thousands-capacity-2026-06-21.md) |
| 对象存储 | [RustFS S3 兼容 POC](./rustfs-poc-2026-09-25.md)、[SeaweedFS S3 兼容 POC](./seaweedfs-poc-2026-09-25.md)、[对齐验证对比](./s3-compatible-backend-comparison-2026-09-25.md) |
| 架构治理 | [面向抽象接口治理](./interface-abstraction-governance-2026-09-25.md)、[Redis / MQ 抽象治理](./redis-mq-abstraction-governance-2026-09-25.md)、[端口化与 Sonar 复核](./infra-port-abstraction-sonar-review-2026-09-25.md)、[bounded context 计划](./bounded-context-migration-plan-2026-08-03.md)、[系统能力差距](./system-wide-capability-gap-analysis-2026-06-20.md)、[行业对标计划](./industry-benchmark-improvement-plan.md) |
| 代码质量 | [语义表达扫描](./java-semantic-expression-scan-2026-08-26.md)、[可读性机器快照](./java-readability-inventory-2026-08-12.md)、[单例与资源生命周期复核](./singleton-resource-lifecycle-review-2026-10-04.md) |
| 历史审计 | [提交审查](./commit-review-2026-06-05-to-09.md)、[上线真实性](./go-live-realism-audit-2026-06-21.md)、[鲁棒性扫描](./robustness-deep-scan-2026-06-10.md)、[SQL 配置治理](./sql-config-governance-report-2026-06-08.md) |
| 历史路线 | [P1A 迁移](./p1a-stage1-migration-plan-2026-05-30.md)、[开源系统差距](./competitive-gap-analysis-2026-05-30.md)、[P0/P1 状态](./p0-p1-governance-status-2026-09-02.md)；均为对应日期的分析，不是当前待办 |

## 工作循环

当前待办的状态分类、归档边界和复扫规则以 [`../standards/document-governance.md`](../standards/document-governance.md) 为准。

```
新事项确认 → 登记 todo-master.md
      ↓
设计/执行计划 → 专题方案或 ADR
      ↓
完成后更新事项状态与验证证据；历史分析快照保持原时点结论
```

当前待办直接在 `todo-master.md` 更新；需要保留审计轨迹的日期快照不覆盖，按目录索引策略移入 `archive/analysis/` 或在本目录明确标记为历史快照。`hardening-backlog.md` 只维护仍有效的硬化事项，不复制完整 issue/fix 历史。

## 归档策略

下列内容原则上归入 `archive/analysis/`；如因代码引用或追溯需要保留在主目录，必须改为带日期的历史快照并从当前事项入口中移除：

1. **一次性 audit / benchmark**：vs-industry 对比、pg-schema-audit、sonar-cleanup、persistence-and-test-architecture 等
2. **已 fold 进 docs/agent-baseline.md / 主干文档的决策档**：原文作为"历史证据"留档，docs/agent-baseline.md 是权威
3. **被新版覆盖的快照**：project-assessment-2026-04-29 这类版本快照

## 与其他子目录的分工

| 目录 | 视角 |
|---|---|
| `analysis/`（本目录） | 当前待办、硬化事项、长期方案与历史分析快照 |
| [`../architecture/`](../architecture/README.md) | 架构现状（"是什么"，不含"该改什么"） |
| [`../runbook/incident-response.md`](../runbook/incident-response.md) | 应急响应（"出问题怎么办"） |
| [`../archive/analysis/`](../archive/analysis/) | 历史快照 / 一次性 audit / 已 fold 的决策档 |
| [`../review/`](../review/) | PR / 模块级**代码深度审查**(reviewer 视角,非架构演进) |

## 即时审计 / 日期快照

按日期命名的专项 deep-scan / SDK round 文件属于**快照证据**。当前权威入口是 `todo-master.md` 与 `hardening-backlog.md`；日期快照仅保留历史证据。

新增日期快照时短期可放在本目录；完成 fold 或失去当前决策价值后移入 `archive/analysis/`，避免主干检索噪音回潮。

## 已归档的近期快照

下列文档已不作为主干权威入口维护，仅保留历史证据：

| 文件 | 归档位置 | 当前替代入口 |
|---|---|---|
| 前后端契约整理记录（2026-05-19） | [`../archive/analysis/frontend-backend-contract-cleanup-2026-05-19.md`](../archive/analysis/frontend-backend-contract-cleanup-2026-05-19.md) | [`../api/console-api-protocol.md`](../api/console-api-protocol.md) + OpenAPI |
| DBA schema 审查（2026-05-20） | [`../archive/analysis/dba-schema-review-2026-05-20.md`](../archive/analysis/dba-schema-review-2026-05-20.md) | [`hardening-backlog.md`](./hardening-backlog.md) + `db/migration/` |
| 2026-05 多轮 deep-scan / go-live / 升级评估快照 | [`../archive/analysis/`](../archive/analysis/) | [`todo-master.md`](./todo-master.md) + [`hardening-backlog.md`](./hardening-backlog.md) |
