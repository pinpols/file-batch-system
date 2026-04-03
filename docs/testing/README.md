# 测试文档索引

这里是 `docs/testing/` 的统一入口，收纳测试计划、门禁规则、执行清单和测试报告。

## 先看这里

1. [full-project-test-plan.md](./full-project-test-plan.md) - 全量测试总计划、阶段拆分和完成状态
2. [phase-1-test-coverage-matrix.md](./phase-1-test-coverage-matrix.md) - 当前测试覆盖盘点和缺口分布
3. [phase-2-functional-regression.md](./phase-2-functional-regression.md) - Phase 2 的 P0 回归范围和后续增量项
4. [release-gate.md](./release-gate.md) - PR / CI / staging 门禁规则和放行标准

## 执行指南

- [staging-live-deploy-smoke-checklist.md](./staging-live-deploy-smoke-checklist.md) - staging 上线 smoke 执行清单
- [deployment-verification-report.md](./deployment-verification-report.md) - 升级 / 回滚验证记录
- [load-test-capacity-baseline.md](./load-test-capacity-baseline.md) - 压测容量基线模板和回填状态

## 测试报告

- [full-test-run-report.md](./full-test-run-report.md) - 最新一次仓库级全量回归结果
- [failure-drill-report.md](./failure-drill-report.md) - 故障演练记录和残余风险
- [e2e-scenario-matrix.md](./e2e-scenario-matrix.md) - E2E 测试类、场景和覆盖状态矩阵
- [e2e-three-flows-coverage.md](./e2e-three-flows-coverage.md) - 三条主链路的 E2E 覆盖分析
- [e2e-individual-run-report.md](./e2e-individual-run-report.md) - 单次 E2E 执行记录

## 说明文档

- [test-strategy.md](./test-strategy.md) - 测试分层策略
- [test-plan.md](./test-plan.md) - 更面向项目层的测试总览和当前状态

## 命名约定

- `phase-1-*` 和 `phase-2-*` 表示阶段性补充文件。
- `full-project-*` 表示跨阶段共用的总计划和参考文档。
- 报告文件尽量按所支持的阶段或门禁命名，保持就近维护。
