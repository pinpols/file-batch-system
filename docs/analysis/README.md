# 演进分析索引

随版本迭代的"问题发现 → 修复 → 硬化"三件套。当前最新都是 v4 系列（2026-04-23），历史多版本在 [`../archive/analysis/`](../archive/analysis/)。

## 文件清单（编号即推荐阅读顺序）

| # | 文件 | 作用 | 何时看 |
|---|---|---|---|
| 01 | [deep-issue-analysis.md](./deep-issue-analysis.md) | 系统级深度问题分析（v4，2026-04-23）：Bug / 漏洞 / 设计缺陷全景 | 想知道"目前有哪些坑" |
| 02 | [fix-report.md](./fix-report.md) | 修复报告（v4，2026-04-22）：哪些已修、谁改的、commit 引用 | 验收 / 复盘 |
| 03 | [hardening-backlog.md](./hardening-backlog.md) | 硬化 backlog（v4，2026-04-20）：剩下的 P0/P1/P2 优先级排序 | 决定下个 sprint 干什么 |
| 04 | [project-assessment-2026-04-29.md](./project-assessment-2026-04-29.md) | 项目工程深度评估快照 v1（2026-04-29）:四维评分 + 7 轮校正历史 | 历史基线参考 |
| 05 | [project-assessment-2026-04-30.md](./project-assessment-2026-04-30.md) | 项目工程深度评估快照 v2（2026-04-30）:24h 演进 delta + 实地 grep 复评 + P1 ops 缺口锁定 | **当前权威**,看"项目今天整体水准"一页式判断 |
| 06 | [positional-args-cleanup-plan.md](./positional-args-cleanup-plan.md) | V6-P2-POSITIONAL-ARGS 治理方案（2026-05-01）：49 处位置参数构造臃肿全清，分 6 批 PR 落地 | 接手该治理 / review 范围与提交策略 |

## 工作循环

```
新 issue 发现  →  追加到 deep-issue-analysis.md
      ↓
   修复落地  →  fix-report.md 记 commit
      ↓
  纳入加固计划 →  hardening-backlog.md 排期
```

每滚一个 vN，旧版整体进 `archive/analysis/`，主干 `.md` 直接覆盖最新版（不留版本号后缀）。

## 与其他子目录的分工

| 目录 | 视角 |
|---|---|
| `analysis/`（本目录） | 演进向：问题 / 修复 / 加固的滚动文档 |
| [`../architecture/architecture-truth.md`](../architecture/architecture-truth.md) | 架构现状（"是什么"，不含"该改什么"） |
| [`../runbook/incident-response.md`](../runbook/incident-response.md) | 应急响应（"出问题怎么办"） |
| [`../archive/analysis/`](../archive/analysis/) | v1/v2/v3 历史快照 |
