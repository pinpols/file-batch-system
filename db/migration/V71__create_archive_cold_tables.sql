-- =========================================================
-- V71 - Create cold archive tables for hot-table slimming
-- =========================================================

CREATE SCHEMA IF NOT EXISTS archive;

CREATE TABLE IF NOT EXISTS archive.outbox_event_archive
    (LIKE batch.outbox_event INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS);
CREATE TABLE IF NOT EXISTS archive.event_delivery_log_archive
    (LIKE batch.event_delivery_log INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS);
CREATE TABLE IF NOT EXISTS archive.event_outbox_retry_archive
    (LIKE batch.event_outbox_retry INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS);

CREATE TABLE IF NOT EXISTS archive.job_instance_archive
    (LIKE batch.job_instance INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS);
CREATE TABLE IF NOT EXISTS archive.job_partition_archive
    (LIKE batch.job_partition INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS);
CREATE TABLE IF NOT EXISTS archive.job_task_archive
    (LIKE batch.job_task INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS);
CREATE TABLE IF NOT EXISTS archive.job_step_instance_archive
    (LIKE batch.job_step_instance INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS);
CREATE TABLE IF NOT EXISTS archive.pipeline_instance_archive
    (LIKE batch.pipeline_instance INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS);
CREATE TABLE IF NOT EXISTS archive.pipeline_step_run_archive
    (LIKE batch.pipeline_step_run INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS);
CREATE TABLE IF NOT EXISTS archive.file_dispatch_record_archive
    (LIKE batch.file_dispatch_record INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS);
CREATE TABLE IF NOT EXISTS archive.workflow_run_archive
    (LIKE batch.workflow_run INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS);
CREATE TABLE IF NOT EXISTS archive.workflow_node_run_archive
    (LIKE batch.workflow_node_run INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS);
CREATE TABLE IF NOT EXISTS archive.job_execution_log_archive
    (LIKE batch.job_execution_log INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS);
CREATE TABLE IF NOT EXISTS archive.compensation_command_archive
    (LIKE batch.compensation_command INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS);

DO $$
DECLARE
    archive_table text;
BEGIN
    FOREACH archive_table IN ARRAY ARRAY[
        'outbox_event_archive',
        'event_delivery_log_archive',
        'event_outbox_retry_archive',
        'job_instance_archive',
        'job_partition_archive',
        'job_task_archive',
        'job_step_instance_archive',
        'pipeline_instance_archive',
        'pipeline_step_run_archive',
        'file_dispatch_record_archive',
        'workflow_run_archive',
        'workflow_node_run_archive',
        'job_execution_log_archive',
        'compensation_command_archive'
    ]
    LOOP
        IF NOT EXISTS (
            SELECT 1
            FROM pg_constraint c
            JOIN pg_class t ON t.oid = c.conrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE n.nspname = 'archive'
              AND t.relname = archive_table
              AND c.contype = 'p'
        ) THEN
            EXECUTE format(
                'ALTER TABLE archive.%I ADD CONSTRAINT %I PRIMARY KEY (id)',
                archive_table,
                'pk_' || archive_table
            );
        END IF;
    END LOOP;
END $$;

CREATE INDEX IF NOT EXISTS idx_outbox_event_archive_created
    ON archive.outbox_event_archive (publish_status, created_at);
CREATE INDEX IF NOT EXISTS idx_event_delivery_log_archive_outbox
    ON archive.event_delivery_log_archive (outbox_event_id);
CREATE INDEX IF NOT EXISTS idx_event_outbox_retry_archive_outbox
    ON archive.event_outbox_retry_archive (outbox_event_id);

CREATE INDEX IF NOT EXISTS idx_job_instance_archive_finished
    ON archive.job_instance_archive (tenant_id, instance_status, finished_at);
CREATE INDEX IF NOT EXISTS idx_job_partition_archive_instance
    ON archive.job_partition_archive (job_instance_id);
CREATE INDEX IF NOT EXISTS idx_job_task_archive_instance
    ON archive.job_task_archive (job_instance_id);
CREATE INDEX IF NOT EXISTS idx_job_step_instance_archive_instance
    ON archive.job_step_instance_archive (job_instance_id);
