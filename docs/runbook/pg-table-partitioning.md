# PostgreSQL 分区表运维

> 本手册按 2026-10-08 仓库状态复核。`outbox_event` 和 `job_instance` 已由 Flyway V172/V173 建立月分区；本文不是分区迁移或数据修复操作手册。生产变更必须通过正式 Flyway 和数据库变更流程执行。

## 事实源与边界

- `db/migration/V172__outbox_event_monthly_partition.sql`：`batch.outbox_event` 按 `created_at` 月分区。
- `db/migration/V173__job_instance_monthly_partition.sql`：`batch.job_instance` 按 `biz_date` 月分区。
- `scripts/db/partition-migration/` 下的 SQL 是迁移演练脚本，见该目录 README；不得把 `01`、`02` 或 `03` 脚本直接用于生产迁移或定时维护。
- 当前仓库未发现负责生产未来分区自动创建的应用调度器或部署 cron。部署团队必须明确分区维护责任、告警和经过审核的执行流程；不得假定分区会自动创建。
- 分区裁剪、分区主键/唯一约束和外键边界会影响查询与幂等语义。不要手工删除约束、旧表或分区，也不要安装 `pg_partman` 等额外扩展来绕过项目迁移流程。

## 只读检查

### 列出分区和边界

```sql
SELECT parent.relname AS parent_table,
       child.relname AS partition_table,
       pg_get_expr(child.relpartbound, child.oid) AS partition_bound
  FROM pg_inherits inheritance
  JOIN pg_class parent ON parent.oid = inheritance.inhparent
  JOIN pg_class child ON child.oid = inheritance.inhrelid
  JOIN pg_namespace ns ON ns.oid = parent.relnamespace
 WHERE ns.nspname = 'batch'
   AND parent.relname IN ('outbox_event', 'job_instance')
 ORDER BY parent.relname, child.relname;
```

### 检查 DEFAULT 分区中的记录

DEFAULT 分区出现记录本身不一定是故障。先确认目标分区是否已创建、记录的业务日期/时间范围及写入来源；以下查询只读：

```sql
SELECT count(*) AS default_outbox_rows
  FROM batch.outbox_event_p_default;

SELECT count(*) AS default_job_instance_rows
  FROM batch.job_instance_p_default;
```

记录数量异常增长、目标分区缺失或 Flyway 迁移状态异常时，保存查询结果、数据库版本、分区边界和相关迁移日志，提交 DBA/值班负责人评估。不要直接 DETACH DEFAULT、复制/删除行或 ATTACH 分区；这些操作可能阻断写入、触发约束扫描、遗漏并发写入，或破坏幂等键和引用关系。

## 新环境与生产变更

1. 新环境只运行仓库 Flyway 迁移，并核对 `flyway_schema_history` 中 V172/V173 成功记录。
2. 已有生产环境的分区结构调整、补历史分区或整理 DEFAULT 分区数据，先按数据库变更流程准备备份、锁/耗时评估、并发写入方案、数据核对和恢复计划。
3. 生产 DDL/DML 变更需由 DBA 审核；不得以本地 rehearsal SQL、手工 `DROP ... CASCADE`、临时关闭安全检查或直接改运行态数据替代正式迁移。
4. 变更后验证分区边界、行数/数据指纹、关键唯一约束与索引、外键/引用完整性、关键 Mapper 查询计划、写入路由及 archive 保留策略，并记录真实环境和执行结果。

## 分区维护责任

V172/V173 建立的初始分区窗口并不证明未来分区会自动创建。当前仓库没有可确认的生产维护调度入口，因此上线配置需显式登记：

- 维护负责人及变更审批人；
- 提前创建分区的周期和时间窗口；
- 分区缺失/DEFAULT 分区增长的告警和处置升级路径；
- 与业务日历、补数范围、保留/归档策略相容的分区窗口。

具体生产自动化方案应作为独立变更评估，优先复用 Flyway/受控 DBA 变更机制，并补充权限最小化、幂等执行、审计、告警和故障恢复测试。本手册不提供未经验证的定时 SQL。

## 相关文件

- [数据库迁移安全技能](../../.agents/skills/database-migration-safety/SKILL.md)
- [分区迁移演练脚本说明](../../scripts/db/partition-migration/README.md)
- [`V172` outbox 分区迁移](../../db/migration/V172__outbox_event_monthly_partition.sql)
- [`V173` job instance 分区迁移](../../db/migration/V173__job_instance_monthly_partition.sql)
- [数据库备份与恢复手册](./backup-and-pitr.md)
