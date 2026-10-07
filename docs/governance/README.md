# 机器可读治理契约

| 文件 | 用途 |
|---|---|
| [java-contract-governance.json](./java-contract-governance.json) | JCON 枚举消费范围和精确到方法签名的动态契约例外 |
| [java-contract-governance-baseline.json](./java-contract-governance-baseline.json) | JCON 存量违规计数基线，当前为空，不使用行号作为标识 |
| [application-governance-contract.yaml](./application-governance-contract.yaml) | 应用治理检查的机器可读契约；由 CI 校验，不在 Markdown 中复制字段 |
| [document-naming-and-archive-policy.md](./document-naming-and-archive-policy.md) | 文档命名、日期后缀和归档边界约束 |
| [soft-gates.json](./soft-gates.json) | 软门禁责任人、当前基线、升级期限与目标 |

人类可读说明见 [`../architecture/application-governance.md`](../architecture/application-governance.md)。
