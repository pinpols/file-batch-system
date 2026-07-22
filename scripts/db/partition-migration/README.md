# 分区迁移演练 SQL

这里放大表分区迁移的演练脚本。

- `01-outbox-event-partitioned.sql`：`outbox_event` 分区化演练。
- `02-job-instance-partitioned.sql`：`job_instance` 分区化演练。
- `03-add-future-partitions.sql`：补未来分区。

这些脚本用于 rehearsal，不是 Flyway 正式迁移。正式落库前需要拆成 `db/migration/V*.sql` 并走迁移安全检查。
