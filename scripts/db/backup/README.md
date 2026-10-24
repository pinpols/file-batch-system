# 数据库备份与恢复演练

这里放 PostgreSQL 备份和 DR drill 脚本。

- `pg-backup.sh`：执行 PostgreSQL 备份。
- `dr-drill.sh`：执行灾备恢复演练。

业务库RLS重建失败或脚本缺失时，演练计为失败，保留恢复库供诊断，不能打印重建成功后再以行数相同判通过。`test_dr_drill_rls_rebuild.py` 只用Docker替身验证这段退出码/保留逻辑，不执行真实恢复，不作为PITR或RPO/RTO证明。

运行前确认目标库、输出目录、凭据来源和保留策略。正式生产演练应结合 [docs/runbook/backup-and-pitr.md](../../../docs/runbook/backup-and-pitr.md)。
