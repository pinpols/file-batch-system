# 机器可读治理契约

| 文件 | 用途 |
|---|---|
| [java-contract-governance.json](./java-contract-governance.json) | JCON 枚举消费范围和精确到方法签名的动态契约例外 |
| [java-contract-governance-baseline.json](./java-contract-governance-baseline.json) | JCON 存量违规计数基线，当前为空，不使用行号作为标识 |
| [application-governance-contract.yaml](./application-governance-contract.yaml) | 应用治理检查的机器可读契约；由 CI 校验，不在 Markdown 中复制字段 |
| [direct-config-key-access-baseline.txt](./direct-config-key-access-baseline.txt) | 直接读取项目配置/密钥 Key 的存量基线；新增命中由专项守卫检查，基线扩张仍需人工审查 |
| [document-naming-and-archive-policy.md](./document-naming-and-archive-policy.md) | 文档命名、日期后缀和归档边界约束 |
| [soft-gates.json](./soft-gates.json) | 软门禁责任人、当前基线、升级期限与目标 |
| [test-conventions-baseline.txt](./test-conventions-baseline.txt) | 测试 `@DisplayName` 存量缺口基线；新增命中由专项守卫检查，基线扩张仍需人工审查 |
| [baseline-expansion-approvals.json](./baseline-expansion-approvals.json) | 基线新增/调高的限期审批记录；每项必须包含 owner、理由、复审日期和移除条件 |

人类可读说明见 [`../architecture/application-governance.md`](../architecture/application-governance.md)。
这些机器可读文件的 PR 路由缺口见[前后端治理漂移复核](../audit/cross-repo-governance-drift-review-2026-10-12.md)。

基线扩张由 PR 与 Full Gate 的 `check-governance-baseline-expansion.py` 对比目标分支判定；新增或调高的条目必须在审批清单登记，过期或已移除条目的审批记录必须清理。审批清单和基线文件由 CODEOWNERS 路由给维护者。降低/删除基线不需要审批。
