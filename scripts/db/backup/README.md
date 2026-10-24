# 数据库备份与恢复演练

这里放 PostgreSQL 备份和 DR drill 脚本。

- `pg-backup.sh`：执行 PostgreSQL 备份。
- `dr-drill.sh`：执行灾备恢复演练。

业务库RLS重建失败或脚本缺失时，演练计为失败，保留恢复库供诊断，不能打印重建成功后再以行数相同判通过。`test_dr_drill_rls_rebuild.py` 只用Docker替身验证这段退出码/保留逻辑，不执行真实恢复，不作为PITR或RPO/RTO证明。

运行前确认目标库、输出目录、凭据来源和保留策略。正式生产演练应结合 [docs/runbook/backup-and-pitr.md](../../../docs/runbook/backup-and-pitr.md)。

## 脚本与配套 SQL 索引

本表登记本目录的执行入口、公共模块及配套 SQL；测试代码通过测试命令执行。新增、移动或删除资产时同步跳转和用途，SQL 正文保持独立维护。

| 文件 | 用途 / 调用归属 |
|---|---|
| [dr-drill.sh](dr-drill.sh) | 本地灾备演练(备份 → 模拟灾难 → 恢复 → 校验 → 量 RTO)。 |
| [pg-backup.sh](pg-backup.sh) | PostgreSQL 每日基准备份 + 两库逻辑导出(+ 可选 WAL 归档清理)。 |
| [sql/count-biz-policies.sql](sql/count-biz-policies.sql) | 供 [dr-drill.sh](dr-drill.sh) 使用的配套 SQL。 |
| [sql/count-customer-accounts.sql](sql/count-customer-accounts.sql) | 供 [dr-drill.sh](dr-drill.sh) 使用的配套 SQL。 |
| [sql/count-job-instances.sql](sql/count-job-instances.sql) | 供 [dr-drill.sh](dr-drill.sh) 使用的配套 SQL。 |
| [sql/count-outbox-events.sql](sql/count-outbox-events.sql) | 供 [dr-drill.sh](dr-drill.sh) 使用的配套 SQL。 |
| [sql/database-exists.sql](sql/database-exists.sql) | 供 [dr-drill.sh](dr-drill.sh) 使用的配套 SQL。 |
| [sql/drop-database.sql](sql/drop-database.sql) | DANGER: 删除传入的整个 PostgreSQL 数据库。只允许由 dr-drill.sh 对本机临时恢复库调用。 |
| [sql/recreate-database.sql](sql/recreate-database.sql) | DANGER: 先 DROP 再创建目标数据库。只允许由 dr-drill.sh 对本机临时恢复库调用。 |
| [sql/select-flyway-version.sql](sql/select-flyway-version.sql) | 供 [dr-drill.sh](dr-drill.sh) 使用的配套 SQL。 |
| [sql/terminate-database-connections.sql](sql/terminate-database-connections.sql) | 供 [dr-drill.sh](dr-drill.sh) 使用的配套 SQL。 |
