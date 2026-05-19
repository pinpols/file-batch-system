-- 把 V130 新建的 console_operation_audit 加入 archive_policy 的白名单。
-- 跟其它运行态表一致,允许运维通过 archive_policy 配置归档天数 / 是否启用 cleanup。
ALTER TABLE batch.archive_policy
    DROP CONSTRAINT ck_archive_policy_table;

ALTER TABLE batch.archive_policy
    ADD CONSTRAINT ck_archive_policy_table CHECK (target_table IN (
        'job_instance','workflow_run','job_partition','file_record',
        'audit_log','outbox_event','event_delivery_log','webhook_delivery_log',
        'console_operation_audit'
    ));
