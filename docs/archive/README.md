# 历史归档索引

归档按当前文档职责分类，有实际材料才建立对应子目录。历史结论不代表当前实现或当前待办；规则见 [文档治理规范](../standards/document-governance.md)，当前路径见 [文档路由](../standards/document-routing-guide.md)。

| 归档目录 | 放什么 | 当前权威入口 |
|---|---|---|
| [architecture/](architecture/README.md) | 历史架构与边界资产 | [当前目录](../architecture/README.md) |
| [audit/](audit/README.md) | 历史系统/专项审计与评估 | [当前目录](../audit/README.md) |
| [design/](design/README.md) | 历史业务设计与方案对比 | [当前目录](../design/README.md) |
| [plans/](plans/README.md) | 历史阶段方案、路线与台账快照 | [当前目录](../plans/README.md) |
| [review/](review/README.md) | 历史代码与差异审查 | [当前目录](../review/README.md) |
| [runbook/](runbook/README.md) | 已被现行手册取代的操作流程 | [当前目录](../runbook/README.md) |
| [standards/](standards/README.md) | 已被现行规范吸收的规则与范围裁决 | [当前目录](../standards/README.md) |
| [stats/](stats/README.md) | 历史统计快照 | [当前目录](../stats/README.md) |
| [testing/](testing/README.md) | 历史测试方法与覆盖矩阵 | [当前目录](../testing/README.md) |
| [verifications/](verifications/README.md) | 历史实测、验收和兼容性证据 | [当前目录](../verifications/README.md) |

## 归档命名与维护

- 一次性材料统一 `<topic>-YYYY-MM-DD.md`；多版本为 `<topic>-vN-YYYY-MM-DD.md`；跨日为 `<topic>-YYYY-MM-DD-to-YYYY-MM-DD.md`。
- 文件日期采用原始基线、审查或执行日期；缺失时查原始 Git 记录，不把归档日当作重新审计日期。
- 关联 ADR 的旧方案、探索与优先级材料以业务主题命名，关联编号写在正文。正式决策入口继续在当前 ADR 索引中维护状态。
- 索引与迁移链接继续维护；历史结论不追溯改写。每个子目录 README 维护全部同级文档的跳转和用途。
- 原 analysis/backlog 分桶已按方案、审计、验证、设计等职责拆分；统计归档统一在 stats/，不再另设 docs/archive/stats/。
