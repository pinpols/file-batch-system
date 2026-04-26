# 测试文档索引

测试计划、覆盖矩阵、release-gate、压测报告。

## 文件清单（编号即推荐阅读顺序）

| # | 文件 | 作用 | 何时看 |
|---|---|---|---|
| 01 | [full-project-test-plan.md](./full-project-test-plan.md) | 全量测试总计划：分层策略、阶段拆分、当前状态 | 入门必看 |
| 02 | [phase-coverage.md](./phase-coverage.md) | Phase 1 + Phase 2 测试覆盖矩阵（合并版）| 想知道"还差什么没测" |
| 03 | [coverage-gap-analysis.md](./coverage-gap-analysis.md) | 覆盖缺口分析（按模块 + 按风险维度）| 排期补测试用例 |
| 04 | [e2e-coverage.md](./e2e-coverage.md) | 三条主链路（IMPORT / EXPORT / DISPATCH）E2E 场景矩阵 | 写新 E2E IT 前 |
| 05 | [release-gate.md](./release-gate.md) | PR / CI / staging 三道门禁规则 + smoke 清单 | 上线 / 评 PR |
| 06 | [realtime-sse-verification.md](./realtime-sse-verification.md) | 实时 SSE 推送链路验证 SOP | console 实时栏目验收 |
| 07 | [load-test-report.md](./load-test-report.md) | 单实例 orchestrator 拐点压测报告（8 req/s）+ 生产容量推算 | 容量规划 |

## 角色路径

| 角色 | 顺序 |
|---|---|
| 新加测试 | 01 → 02 → 03 |
| Review PR | 05 |
| 容量 / 性能 | 07 → [`../architecture/scalability-assessment.md`](../architecture/scalability-assessment.md) |
| 写新 E2E | 04 → [`../runbook/worker-stage-coverage.md`](../runbook/worker-stage-coverage.md) |

## 与其他子目录的分工

| 目录 | 视角 |
|---|---|
| `testing/`（本目录） | 质量向：计划 / 矩阵 / 门禁 / 压测 |
| [`../runbook/worker-stage-coverage.md`](../runbook/worker-stage-coverage.md) | 端到端验证手册（运维角度）|
| [`../runbook/quartz-capacity-baseline.md`](../runbook/quartz-capacity-baseline.md) | Quartz 容量压测（运维角度）|
| [`../analysis/`](../analysis/README.md) | 测试中发现问题的滚动记录 |
| [`../archive/testing/`](../archive/testing/) | 历史压测报告 / 测试运行快照 |
