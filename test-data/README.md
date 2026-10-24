# 共享测试数据

本目录是可执行测试资产，不属于 docs/。数据内容跟随场景和契约变化维护，不使用审计报告的日期命名规则。

| 目录 | 用途 | 消费方 |
|---|---|---|
| [sim/](sim/) | SIM 初始化、故障注入、回读 SQL 和参数 JSON | scripts/sim、脚本契约测试、多模块测试 |
| [tenant-config-packages/](tenant-config-packages/README.md) | 四份租户配置包 XLSX | SIM 导入、配置包生成器和 fixture 检查 |

测试方法与迁移规则见 [测试数据指南](../docs/testing/test-fixture-guide.md)。模块专用 fixture 仍放 src/test/resources/；生产初始化与 Flyway 迁移不放本目录。
