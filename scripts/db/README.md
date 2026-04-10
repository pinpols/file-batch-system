# SQL 文档索引

这里收纳数据库结构、初始化脚本、系统测试种子和压测种子。

## 目录说明

- [flyway/](./flyway/) - 平台 Flyway **说明**（`V*.sql` 仅维护在 `db/migration/`）
- [business/](./business/) - 业务库 `batch_business.biz` 的示例建模脚本
- [system-test/](./system-test/) - 系统测试用的可重复种子数据
- [load-test/](./load-test/) - 压测专用种子数据

## 使用顺序

1. 本地联调先看 [docs/runbook/local-development.md](../runbook/local-development.md)
2. 平台库结构变更（含 Quartz 元数据表）只改 [db/migration](../../db/migration/)，并阅读 [flyway/README.md](./flyway/README.md) 了解约定
3. 业务库示例建模走 [business/README.md](./business/README.md)
4. 系统测试数据装载走 [system-test/README.md](./system-test/README.md)
5. 压测种子数据走 [load-test/README.md](./load-test/README.md)

## 脚本分类

- `V*__.sql`：Flyway 迁移脚本
- `*_seed.sql`：系统测试或压测种子
- `*_edge_cases.sql`：系统测试边界样本

## 参考文档

- [docs/testing/README.md](../testing/README.md)
- [sql-script-usage-scenarios.md](./sql-script-usage-scenarios.md)
