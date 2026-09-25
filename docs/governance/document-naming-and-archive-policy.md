# 文档命名与归档策略

本文约束 `docs/` 下文档的命名、是否带日期、以及何时归档。

## 当前生效文档

当前生效文档不带日期。它们表达系统当前事实、操作入口或稳定契约，后续更新应直接修改原文件。

适用目录：

- `docs/api/`
- `docs/architecture/adr/`
- `docs/design/`
- `docs/governance/`
- `docs/runbook/`
- `docs/sdk/`
- `docs/standards/`
- `docs/testing/`
- `docs/stats/loc-current-lean.md`

例外：如果文件本质是一次性事件复盘、临时迁移方案或历史验证记录，即使暂存在这些目录，也应尽快迁入 `docs/archive/` 或对应报告目录。

## 一次性报告

一次性报告应带日期，便于按时间追溯上下文，不应被当作当前事实入口。

适用目录：

- `docs/analysis/`
- `docs/audit/`
- `docs/review/`
- `docs/verifications/`
- `docs/backlog/`
- `docs/plans/`

命名建议：

- `topic-name-YYYY-MM-DD.md`
- 月度或阶段性材料可用 `topic-name-YYYY-MM.md`
- 多轮审计或活动记录可在主题中写轮次，但仍保留日期

## 归档规则

满足任一条件时应归档到 `docs/archive/<原目录>/`：

- 已被新的当前生效文档替代。
- 只是某次上线、审计、压测或迁移的历史证据。
- 文件名带日期，但所在目录表达当前运行入口，例如 `runbook`、`design`、`architecture`。
- README/索引已不再引用，且没有 CI 或 runbook 直接依赖。

归档时必须：

- 保留原文件名，避免历史链接语义丢失。
- 更新所在目录 `README.md`。
- 修正仍指向旧路径的仓库内链接。

## 当前核查结论

截至 2026-09-25，`docs/` 下 Git 跟踪 Markdown 带日期文档约 203 个、不带日期文档约 280 个。整体分布基本合理：

- `analysis/audit/review/verifications/backlog/plans` 中的日期文档符合一次性报告定位。
- `docs/stats` 当前快照已收敛为 `loc-current-lean.md`，历史快照放入 `docs/stats/archive/`。
- `runbook` 中已确认的历史切换记录放入 `docs/runbook/archive/`，当前运维入口保持稳定文件名。
- `architecture/design/sdk` 中仍有少量带日期的历史材料，已登记在
  `scripts/ci/check-doc-timestamp-policy.py` 白名单；后续新增同类文件会被 CI 拦截，存量文件按是否仍被引用逐步迁入对应 `archive/` 或改为稳定文件名。

## 守护

文档命名策略由 `scripts/ci/check-doc-timestamp-policy.py` 校验：

- 当前事实目录禁止新增带日期文件名，除非先评审并登记为历史例外。
- 报告、审计、复核、验证、backlog、plan 默认应带日期或阶段标识。
- 归档目录不参与当前事实判定，但仓库内非归档文档不得链接到不存在的归档目标。
