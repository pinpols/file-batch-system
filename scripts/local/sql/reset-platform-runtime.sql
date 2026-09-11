DO $reset$
DECLARE
    relation record;
BEGIN
    FOR relation IN
        SELECT tablename
        FROM pg_tables
        WHERE schemaname = 'batch'
          AND tablename ~ '^(job_instance|job_execution|pipeline_instance|pipeline_step_run|pipeline_progress|workflow_run|workflow_node_run|retry_schedule|outbox_event|event_outbox_retry|trigger_outbox_event|worker_report_outbox|file_record|file_error_record|file_channel_health|compensation_command|dead_letter_task)'
    LOOP
        EXECUTE format('TRUNCATE TABLE batch.%I CASCADE', relation.tablename);
    END LOOP;
END
$reset$;
