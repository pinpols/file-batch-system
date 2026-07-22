# 数据库备份与恢复演练

这里放 PostgreSQL 备份和 DR drill 脚本。

- `pg-backup.sh`：执行 PostgreSQL 备份。
- `dr-drill.sh`：执行灾备恢复演练。

运行前确认目标库、输出目录、凭据来源和保留策略。正式生产演练应结合 [docs/runbook/backup-and-pitr.md](../../../docs/runbook/backup-and-pitr.md)。
