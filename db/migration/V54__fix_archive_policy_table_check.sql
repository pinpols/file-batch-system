-- 修正 archive_policy CHECK 约束中的错误表名：
-- outbox_delivery_log 表不存在，实际表名为 event_delivery_log。
ALTER TABLE batch.archive_policy
    DROP CONSTRAINT ck_archive_policy_table;

ALTER TABLE batch.archive_policy
    ADD CONSTRAINT ck_archive_policy_table CHECK (target_table IN (
        'job_instance','workflow_run','job_partition','file_record',
        'audit_log','outbox_event','event_delivery_log','webhook_delivery_log'
    ));
