# 测试文档索引

测试计划、历史覆盖矩阵、当前 CI 门禁边界、压测报告与验收流程。

## 文件清单（编号即推荐阅读顺序）

| # | 文件 | 作用 | 何时看 |
|---|---|---|---|
| 01 | [full-project-test-plan.md](./full-project-test-plan.md) | 测试分层与历史阶段计划；当前待办以 todo-master 为准 | 了解测试策略 |
| 02 | [phase-coverage.md](./phase-coverage.md) | 历史测试覆盖矩阵；不代表当前缺口 | 查阅阶段背景 |
| 03 | [coverage-gap-analysis.md](./coverage-gap-analysis.md) | 最近一次覆盖盘点（注明核查日期）；实时状态仍以代码和 CI 为准 | 了解覆盖现状与候选缺口 |
| 04 | [e2e-coverage.md](./e2e-coverage.md) | 旧 E2E 矩阵迁移提示；历史矩阵已归档 | 查看归档入口 |
| 05 | [release-gate.md](./release-gate.md) | PR 合入门禁、main 回归、nightly E2E 与本地发布验收能力边界 | 上线 / 评 PR |
| 06 | [realtime-sse-verification.md](./realtime-sse-verification.md) | 实时 SSE 推送链路验证 SOP | console 实时栏目验收 |
| 07 | [load-test-report.md](./load-test-report.md) | 单实例 orchestrator 拐点压测报告（8 req/s）+ 生产容量推算 | 容量规划 |
| 08 | [load-test-dimensions.md](./load-test-dimensions.md) | 压测维度矩阵：调度快照 / 端到尾完成 / 与指标分工 | 扩展 Gatling 场景前必读 |

## 角色路径

| 角色 | 顺序 |
|---|---|
| 新加测试 | 03 → 检查现有测试类与 `scripts/local/run-tests.sh --e2e` |
| Review PR | 05 |
| 容量 / 性能 | 07 → 08 → [`../architecture/scalability-assessment.md`](../architecture/scalability-assessment.md) |
| 写新 E2E | 03 → [`../runbook/worker-stage-coverage.md`](../runbook/worker-stage-coverage.md) |

## 与其他子目录的分工

| 目录 | 视角 |
|---|---|
| `testing/`（本目录） | 质量向：计划 / 矩阵 / 门禁 / 压测 |
| [`../runbook/worker-stage-coverage.md`](../runbook/worker-stage-coverage.md) | 端到端验证手册（运维角度）|
| [`../runbook/quartz-capacity-baseline.md`](../runbook/quartz-capacity-baseline.md) | Quartz 容量压测（运维角度）|
| [`../analysis/`](../analysis/README.md) | 测试中发现问题的滚动记录 |
| [`../archive/testing/`](../archive/testing/) | 历史压测报告 / 测试运行快照 |
