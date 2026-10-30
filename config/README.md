# 工程配置

本目录保存随仓库版本维护、由构建或工程脚本消费的配置。应用自身的配置仍归各模块 `src/main/resources/application*.yml`；本地环境覆盖见根 `.env.example`，部署覆盖见 [Helm 目录](../helm/README.md)。

| 入口 | 职责 / 消费方 |
|---|---|
| [quality/](quality/README.md) | PMD / SpotBugs 质量规则；根 Maven 构建与质量回归共用 |
| [ops-governance/](ops-governance/README.md) | 本地、测试、压测、生产的基础设施治理 profile；由 scripts/ops 入口读取并适配到目标环境 |
| [runtime-warning-policy.tsv](runtime-warning-policy.tsv) | 本地运行 WARN 的分类、责任人与有效期；由运行日志审计读取 |
| [local-log-retention.json](local-log-retention.json) | 本地日志原文/归档保留期及单次大小限制；由 manage-logs.py 读取 |

配置与脚本行为同步维护，避免在多个入口复制同一默认值。真实凭据使用环境变量、受管 Secret 或被忽略的本地文件。执行说明见 [本地开发](../docs/runbook/local-development.md)、[本地日志](../docs/runbook/local-log-layout.md)与 [脚本入口](../scripts/README.md)。
