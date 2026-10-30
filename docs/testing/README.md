# 测试文档索引

测试计划、历史覆盖矩阵、当前 CI 门禁边界、压测报告与验收流程。

## 文件清单（编号即推荐阅读顺序）

| # | 文件 | 作用 | 何时看 |
|---|---|---|---|
| 01 | [full-project-test-plan.md](./full-project-test-plan.md) | 测试分层与历史阶段计划；当前待办以 todo-master 为准 | 了解测试策略 |
| 04 | [e2e-coverage.md](./e2e-coverage.md) | 旧 E2E 矩阵迁移提示；历史矩阵已归档 | 查看归档入口 |
| 05 | [release-gate.md](./release-gate.md) | PR 合入门禁、main/每周回归、手动 E2E 复验与本地发布验收边界 | 上线 / 评 PR |
| 06 | [realtime-sse-verification.md](./realtime-sse-verification.md) | 实时 SSE 推送链路验证 SOP | console 实时栏目验收 |
| 08 | [load-test-dimensions.md](./load-test-dimensions.md) | 压测维度矩阵：调度快照 / 端到尾完成 / 与指标分工 | 扩展 Gatling 场景前必读 |
| 09 | [backend-sim-reliability.md](./backend-sim-reliability.md) | 后端 Sim 可靠性场景组：文件完整性、DB 并发、故障隔离与恢复的覆盖矩阵及验证分层 | 盘点可靠性场景、扩展 Sim 或安排 staging 演练 |

## 历史覆盖与压测

当前测试方法见[全项目测试计划](full-project-test-plan.md)，场景入口见[E2E矩阵](e2e-coverage.md)。旧覆盖缺口和Phase矩阵中的有效要求已并入现行文档；[2026-04-25压测报告](../verifications/load-test-report-2026-04-25.md)只作历史证据。当前测试资产、执行范围和结果以源码、CI、[发布门禁](release-gate.md)与最新验证证据为准。

## 角色路径

| 角色 | 顺序 |
|---|---|
| 新加测试 | 01 → 检查现有测试类与 `scripts/local/run-tests.sh --e2e` |
| Review PR | 05 |
| 容量 / 性能 | 08 → [容量治理](../runbook/production-capacity-governance.md) → [验证证据](../verifications/README.md) |
| 写新 E2E | 09 → [验收手册](../runbook/be-acceptance.md) |
| 可靠性 / 故障恢复 | 09 → [`../design/file-integrity-recovery.md`](../design/file-integrity-recovery.md) → [`../runbook/go-live-staging-execution.md`](../runbook/go-live-staging-execution.md) |

## 与其他子目录的分工

| 目录 | 视角 |
|---|---|
| `testing/`（本目录） | 质量向：计划 / 矩阵 / 门禁 / 压测 |
| [历史 Worker Stage 验证](../verifications/worker-stage-coverage-2026-04-25.md) | 2026-04-25 的运行证据，不作为当前覆盖率 |
| [`../runbook/quartz-capacity-baseline.md`](../runbook/quartz-capacity-baseline.md) | Quartz 容量压测（运维角度）|
| [`../analysis/`](../plans/README.md) | 测试中发现问题的滚动记录 |
| [历史验证证据](../archive/verifications/README.md) | 带原基线的测试运行与优化记录 |

## 专题入口

| 文档 | 职责 |
|---|---|
| [be-acceptance-template.md](be-acceptance-template.md) | BE 验收 backlog — <date> |

- [test-fixture-guide.md](test-fixture-guide.md)：测试资产位置、调用方和路径迁移验证规则。
