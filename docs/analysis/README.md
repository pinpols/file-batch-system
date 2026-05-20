# 演进分析索引

随版本迭代的"问题发现 → 修复 → 硬化"主线，加上少量长期治理方案与项目评估快照。一次性的 audit / benchmark / 已 fold 进 CLAUDE.md 的决策档，全部归 [`../archive/analysis/`](../archive/analysis/)。

## 文件清单（编号即推荐阅读顺序）

| # | 文件 | 作用 | 何时看 |
|---|---|---|---|
| 01 | [deep-issue-analysis.md](./deep-issue-analysis.md) | 系统级深度问题分析：Bug / 漏洞 / 设计缺陷全景 | 想知道"目前有哪些坑" |
| 02 | [fix-report.md](./fix-report.md) | 修复报告：哪些已修、commit 引用 | 验收 / 复盘 |
| 03 | [hardening-backlog.md](./hardening-backlog.md) | 硬化 backlog：剩下的 P0/P1/P2 优先级排序 | 决定下个 sprint 干什么 |
| 04 | [project-assessment.md](./project-assessment.md) | 项目工程深度评估（当前权威）：四维评分 + 演进 delta + ops 缺口锁定 | 看"项目今天整体水准"一页式判断 |
| 05 | [positional-args-cleanup-plan.md](./positional-args-cleanup-plan.md) | 位置参数构造臃肿治理方案 v4（已闭环）：CLAUDE.md "调用方约束" 子节由本方案沉淀 | review 该治理范围 / 历史决策溯源 |
| 06 | [system-scope-boundary.md](./system-scope-boundary.md) | 系统职责范围基准：批量调度 + 文件交付闭环的边界守护 | 判定新功能是否越界、季度复盘 |
| 07 | [todo-master.md](./todo-master.md) | 全仓待办整合：跨 docs/ 与代码注解的统一 backlog | 想知道"还有什么没干" |
| 08 | [frontend-backend-contract-cleanup-2026-05-19.md](./frontend-backend-contract-cleanup-2026-05-19.md) | 前后端契约与文档整理记录：OpenAPI 路径漂移、Job Bundle 事务语义、前端导入闭环 | 修 Console API / Job Bundle / 前端联调前 |
| 09 | [dba-schema-review-2026-05-20.md](./dba-schema-review-2026-05-20.md) | DBA 审查报告：Schema / 索引 / 分区归档 / 约束四维 Top 10 问题 + Quick wins + 多日重构 | 评估 DB 健康度、排期 partition / 索引整合 / 生命周期补齐 |

## 工作循环

```
新 issue 发现  →  追加到 deep-issue-analysis.md
      ↓
   修复落地  →  fix-report.md 记 commit
      ↓
  纳入加固计划 →  hardening-backlog.md 排期
```

每滚一个 vN，旧版整体进 `archive/analysis/`，主干 `.md` 直接覆盖最新版（不留版本号后缀）。

## 归档策略

下列三类一律落 `archive/analysis/`，不在主干维护：

1. **一次性 audit / benchmark**：vs-industry 对比、pg-schema-audit、sonar-cleanup、persistence-and-test-architecture 等
2. **已 fold 进 CLAUDE.md / 主干文档的决策档**：原文作为"历史证据"留档，CLAUDE.md 是权威
3. **被新版覆盖的快照**：project-assessment-2026-04-29 这类版本快照

## 与其他子目录的分工

| 目录 | 视角 |
|---|---|
| `analysis/`（本目录） | 演进向：问题 / 修复 / 加固的滚动文档 + 长期治理方案 |
| [`../architecture/`](../architecture/README.md) | 架构现状（"是什么"，不含"该改什么"） |
| [`../runbook/incident-response.md`](../runbook/incident-response.md) | 应急响应（"出问题怎么办"） |
| [`../archive/analysis/`](../archive/analysis/) | 历史快照 / 一次性 audit / 已 fold 的决策档 |
