# 项目审查来源与技能路由

本表是发现入口，不复制领域规则。文件可能继续演进；使用时先看目录索引和当前版本。

## 当前入口

| 主题 | 权威发现入口 | 用途 |
|---|---|---|
| 项目硬约束与编码基线 | `docs/agent-baseline.md`、`docs/coding-conventions.md` | 架构红线、模块边界、编码和测试约定 |
| 当前架构与决策 | `docs/architecture/README.md`、`docs/architecture/architecture-truth.md`、`docs/architecture/adr/README.md` | 当前拓扑、模块所有权、ADR 决策及状态 |
| 当前事项与工程评估 | `docs/analysis/README.md`、`docs/analysis/todo-master.md`、`docs/analysis/hardening-backlog.md` | 当前待办、仍有效的硬化项和分析索引 |
| 专项审计与代码评审 | `docs/audit/README.md`、`docs/review/README.md` | 近期审计、模块评审及审计边界 |
| 规约漂移与 CI 守卫 | `docs/audit/convention-drift-guard-index.md`、`scripts/ci/README.md`、`.github/workflows/` | 从约定追踪到可执行门禁和 CI 覆盖 |
| 安全与信任边界 | `docs/architecture/security-model.md`、`SECURITY.md`、`docs/standards/open-source-governance.md` | 资产、信任边界、安全响应与供应链 |
| 运维与验证证据 | `docs/runbook/README.md`、`docs/testing/README.md`、`docs/verifications/README.md` | 运维操作、测试计划、实测记录和环境证据 |
| 数据与 API | `docs/design/README.md`、`db/migration/`、`docs/api/README.md` | 当前 schema、迁移、数据模型和服务契约 |
| 文档生命周期 | `docs/standards/document-governance.md`、`docs/archive/README.md` | 权威状态、历史材料和归档边界 |

## 历史审查材料的使用

审查跨多个历史材料时，先阅读 `docs/archive/README.md`、`docs/analysis/README.md`、`docs/audit/README.md` 和 `docs/review/README.md`，再依任务主题检索所有相关当前及归档报告，例如：

```bash
rg --files docs/analysis docs/audit docs/review docs/archive \
  | rg -i '(audit|review|assessment|deep|scan|governance|security|capacity|architecture)'
```

按主题抽取反复出现的故障模式、被修复后复发的问题、误报原因和验证缺口；将这些作为当前代码的检查假设逐项核实。不要把归档材料中的绝对结论、旧版本路径、风险等级或“已修复”状态直接复制成当前判断。`docs/archive/README.md` 规定归档只读，不因技能提炼而批量改写历史原文。

## 专项技能路由

| 工作主体 | 技能 |
|---|---|
| 单个 PR / diff 正确性 | `code-review-and-gates` |
| 跨信任边界的系统级攻击与故障审查 | `adversarial-system-review` |
| Worker、Outbox、Kafka、claim/report、恢复 | `worker-pipeline-review` |
| DB 迁移、SQL 性能、灾备和验收 | `database-migration-safety`、`sql-query-performance`、`disaster-recovery-validation`、`acceptance-validation` |
| Java/模块/配置与 API/SDK 边界 | `module-config-boundaries`、`configuration-governance`、`frontend-backend-contract`、`sdk-contract-governance` |
| 安全扫描、CI/测试与供应链 | `security-scan-governance`、`ci-governance` |
| 调度、对象存储、脚本 SQL、性能容量 | `scheduler-correctness`、`object-storage-governance`、`script-sql-governance`、`performance-validation` |
| 文档与 PR 交付 | `documentation-governance`、`git-pr-workflow` |
| 批量业务、分布式执行与调度的领域设计/开发/评估 | `batch-scheduling-engineering` |
