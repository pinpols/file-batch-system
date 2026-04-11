-- Fix incorrect table name in archive_policy CHECK constraint:
-- outbox_delivery_log does not exist; the actual table is event_delivery_log.
ALTER TABLE batch.archive_policy
    DROP CONSTRAINT ck_archive_policy_table;

ALTER TABLE batch.archive_policy
    ADD CONSTRAINT ck_archive_policy_table CHECK (target_table IN (
        'job_instance','workflow_run','job_partition','file_record',
        'audit_log','outbox_event','event_delivery_log','webhook_delivery_log'
    ));
