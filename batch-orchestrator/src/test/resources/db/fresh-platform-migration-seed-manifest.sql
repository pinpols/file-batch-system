WITH seeded_rows AS (
    SELECT
        'SEED_ROW'::text AS row_type,
        jsonb_build_array(
            'batch.tenant',
            t.tenant_id,
            to_jsonb(t) - ARRAY['id', 'created_at', 'updated_at']
        ) AS manifest_row
    FROM batch.tenant t

    UNION ALL

    SELECT
        'SEED_ROW',
        jsonb_build_array(
            'batch.console_user_account',
            a.tenant_id || '/' || a.username,
            to_jsonb(a) - ARRAY['id', 'created_at', 'updated_at']
        )
    FROM batch.console_user_account a

    UNION ALL

    SELECT
        'SEED_ROW',
        jsonb_build_array(
            'batch.resource_queue',
            q.tenant_id || '/' || q.queue_code,
            to_jsonb(q) - ARRAY['id', 'created_at', 'updated_at']
        )
    FROM batch.resource_queue q

    UNION ALL

    SELECT
        'SEED_ROW',
        jsonb_build_array(
            'batch.tenant_quota_policy',
            q.tenant_id || '/' || q.policy_code,
            to_jsonb(q) - ARRAY['id', 'created_at', 'updated_at']
        )
    FROM batch.tenant_quota_policy q

    UNION ALL

    SELECT
        'SEED_ROW',
        jsonb_build_array(
            'batch.batch_window',
            w.tenant_id || '/' || w.window_code,
            to_jsonb(w) - ARRAY['id', 'created_at', 'updated_at']
        )
    FROM batch.batch_window w

    UNION ALL

    SELECT
        'SEED_ROW',
        jsonb_build_array(
            'batch.business_calendar',
            c.tenant_id || '/' || c.calendar_code,
            to_jsonb(c) - ARRAY['id', 'created_at', 'updated_at']
        )
    FROM batch.business_calendar c

    UNION ALL

    SELECT
        'SEED_ROW',
        jsonb_build_array(
            'batch.file_template_config',
            c.tenant_id || '/' || c.template_code || '/' || c.version,
            to_jsonb(c) - ARRAY['id', 'created_at', 'updated_at']
        )
    FROM batch.file_template_config c

    UNION ALL

    SELECT
        'SEED_ROW',
        jsonb_build_array(
            'batch.archive_policy',
            p.tenant_id || '/' || p.target_table,
            to_jsonb(p) - ARRAY['id', 'created_at', 'updated_at']
        )
    FROM batch.archive_policy p

    UNION ALL

    SELECT
        'SEED_ROW',
        jsonb_build_array(
            'batch.batch_runtime_default_parameter',
            p.module || '/' || p.parameter_key,
            to_jsonb(p) - ARRAY['id', 'created_at', 'updated_at']
        )
    FROM batch.batch_runtime_default_parameter p

    UNION ALL

    SELECT
        'SEED_ROW',
        jsonb_build_array(
            'batch.console_maintenance_state',
            s.id::text,
            to_jsonb(s) - ARRAY['updated_at']
        )
    FROM batch.console_maintenance_state s

    UNION ALL

    SELECT
        'SEED_ROW',
        jsonb_build_array(
            'batch.tenant_configuration_revision',
            r.tenant_id,
            to_jsonb(r) - ARRAY['updated_at']
        )
    FROM batch.tenant_configuration_revision r

    UNION ALL

    SELECT
        'SEED_ROW',
        jsonb_build_array(
            'batch.waiting_partition_scan_cursor',
            c.singleton_id::text,
            to_jsonb(c) - ARRAY['updated_at']
        )
    FROM batch.waiting_partition_scan_cursor c

    UNION ALL

    SELECT
        'BACKFILL_TARGET',
        jsonb_build_array(target.table_name, target.row_count)
    FROM (
        SELECT 'batch.result_version' AS table_name, count(*) AS row_count
        FROM batch.result_version
        UNION ALL
        SELECT 'batch.trigger_request', count(*) FROM batch.trigger_request
        UNION ALL
        SELECT 'batch.job_dependency', count(*) FROM batch.job_dependency
        UNION ALL
        SELECT 'batch.outbox_event_dedup_key', count(*) FROM batch.outbox_event_dedup_key
        UNION ALL
        SELECT 'batch.job_instance_dedup_key', count(*) FROM batch.job_instance_dedup_key
        UNION ALL
        SELECT 'batch.tenant_configuration_revision', count(*)
        FROM batch.tenant_configuration_revision
        UNION ALL
        SELECT 'batch.scheduled_occurrence_supersession_history', count(*)
        FROM batch.scheduled_occurrence_supersession_history
        UNION ALL
        SELECT 'batch.outbox_event', count(*) FROM batch.outbox_event
        UNION ALL
        SELECT 'batch.job_instance', count(*) FROM batch.job_instance
    ) target
)
SELECT jsonb_build_array(row_type, manifest_row)::text
FROM seeded_rows
ORDER BY row_type, manifest_row;
