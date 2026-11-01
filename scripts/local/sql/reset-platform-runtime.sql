DO $$
BEGIN
    IF current_setting('batch.destructive_ops', true) IS DISTINCT FROM 'sim-reset' THEN
        RAISE EXCEPTION '拒绝重置平台运行态:需通过本地 sim reset 入口显式授权';
    END IF;
END
$$;


SET citus.multi_shard_modify_mode TO 'sequential';


DO $reset$
DECLARE
    relation record;
BEGIN
    FOR relation IN
        SELECT tablename
        FROM pg_tables
        WHERE schemaname = 'batch'
          AND (tablename ~ '^(job_instance|job_execution|pipeline_instance|pipeline_step_run|pipeline_progress|workflow_run|workflow_node_run|retry_schedule|outbox_event|event_outbox_retry|trigger_outbox_event|worker_report_outbox|file_record|file_error_record|file_channel_health|compensation_command|dead_letter_task)'
               OR tablename = ANY (ARRAY[
                   'job_task', 'job_partition', 'job_step_instance',
                   'trigger_request', 'trigger_misfire_pending', 'workflow_launch_wait',
                   'event_delivery_log', 'file_dispatch_record', 'file_audit_log',
                   'custom_task_type_registry', 'worker_registry', 'process_staging',
                   'console_operation_audit', 'console_ai_audit_log',
                   'batch_day_operation_audit', 'batch_day_instance', 'batch_day_waiting_launch',
                   'batch_day_replay_session', 'batch_day_replay_entry', 'batch_day_replay_preview_token',
                   'batch_day_open_cursor', 'batch_day_catchup_control_event',
                   'calendar_dependency_wait', 'job_dependency_waiting_launch',
                   'scheduled_occurrence', 'scheduled_occurrence_coverage', 'scheduled_occurrence_fire',
                   'scheduled_occurrence_supersession_history', 'scheduled_occurrence_catch_up_audit',
                   'expected_work_occurrence', 'trigger_request_idempotency_alias',
                   'console_push_job_delivery',
                   'workflow_terminal_notification_receipt', 'webhook_delivery_log', 'notification_delivery_log'
               ]))
    LOOP
        EXECUTE format('TRUNCATE TABLE batch.%I CASCADE', relation.tablename);
    END LOOP;
END
$reset$;
