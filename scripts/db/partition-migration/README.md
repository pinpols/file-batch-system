# 分区迁移演练 SQL

这里放大表分区迁移的演练脚本。

- `01-outbox-event-partitioned.sql`：`outbox_event` 分区化演练。
- `02-job-instance-partitioned.sql`：`job_instance` 分区化演练。
- `03-add-future-partitions.sql`：补未来分区。

这些脚本用于 rehearsal，不是 Flyway 正式迁移。正式落库前需要拆成 `db/migration/V*.sql` 并走迁移安全检查。

## 已知审查项

2026-10-18：[分区扩展专项 PART-03/04/07/08](../../../docs/plans/database-partition-lifecycle-remediation-2026-10-18.md)记录了持续维护责任、锁/超时、时区、同名对象校验和 DEFAULT 处置等独立整改项；本目录不代表已部署的自动维护能力。

`03` 的事务合同为整批原子：内层异常块继续尝试并汇总失败，末尾任一失败会撤销整个 DO 本轮成功的 DDL，并以非零状态退出；执行前已提交的分区不受影响。只有全部成功才提交本轮新分区，重复执行不会重复创建。DEFAULT 仅在目标新边界内已有行时产生约束冲突，其它月份的行不阻止该边界创建。

2026-10-23 本次仅校正上述事务与 DEFAULT 边界注释，移除文件头未经部署的 cron/直接迁移指引，没有改变 SQL 行为或增加自动维护器。两表演练的实际命令和结果由整合验收记录维护。

## 脚本与配套 SQL 索引

本表登记本目录的执行入口、公共模块及配套 SQL；测试代码通过测试命令执行。新增、移动或删除资产时同步跳转和用途，SQL 正文保持独立维护。

| 文件 | 用途 / 调用归属 |
|---|---|
| [01-outbox-event-partitioned.sql](01-outbox-event-partitioned.sql) | outbox_event 改造为按 created_at 月分区。配合 02-job-instance-partitioned.sql 使用。 |
| [02-job-instance-partitioned.sql](02-job-instance-partitioned.sql) | job_instance 改造为按 biz_date 月分区。配合 01-outbox-event-partitioned.sql 使用。 |
| [03-add-future-partitions.sql](03-add-future-partitions.sql) | 演练入口：在 outbox_event / job_instance 上创建当前月到未来 N 个月的分区。 |
