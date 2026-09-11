# 数据库脚本目录说明

这里收纳非 Flyway 的数据库维护脚本：业务库样例、测试种子、压测种子、清理、备份恢复和分区迁移演练。

## 目录分工

- [business/](./business/)：业务库 `batch_business.biz` 的示例建模、RLS 和分区脚本。
- [test-seed/](./test-seed/)：平台库与业务库的系统测试种子。
- [load-test-seed/](./load-test-seed/)：压测场景初始化数据。
- [backup/](./backup/)：PostgreSQL 备份、恢复和 DR drill 脚本。
- [cleanup/](./cleanup/)：测试、压测和临时数据清理 SQL。
- [partition-migration/](./partition-migration/)：大表分区迁移演练 SQL。

平台正式结构变更只维护在 [db/migration/](../../db/migration/)；本目录脚本用于环境准备、演练和一次性维护。

## 常用入口

1. 本地联调先看 [docs/runbook/local-development.md](../../docs/runbook/local-development.md)。
2. 业务库示例建模走 [business/README.md](./business/README.md)。
3. 系统测试数据装载走 [test-seed/README.md](./test-seed/README.md)。
4. 压测种子数据走 [load-test-seed/README.md](./load-test-seed/README.md)。
5. 备份恢复演练走 [backup/README.md](./backup/README.md)。
6. 分区迁移演练走 [partition-migration/README.md](./partition-migration/README.md)。
7. 外置 PostgreSQL 上线前运行 `check-postgres-control-plane-readiness.sh`；生产设置
   `PG_READINESS_STRICT=1`，完整参数见 [deploy/ha/README.md](../../deploy/ha/README.md)。

预检 SQL 独立保存在 `postgres-control-plane-readiness.sql`，便于 DBA 先审 SQL 再执行。
`PG_EXPECTED_APP_CONNECTIONS` 应填写所有应用副本 Hikari 上限总和，`PG_CONNECTION_RESERVE`
用于预留运维、迁移和复制连接。

## 参考文档

- [docs/testing/README.md](../../docs/testing/README.md)
- [sql-script-usage-scenarios.md](./sql-script-usage-scenarios.md)
