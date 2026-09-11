WITH latest AS (
    SELECT id, tenant_id, instance_status, params_snapshot
    FROM batch.job_instance
    WHERE tenant_id = :'tenant_id' AND job_code = :'job_code'
      AND created_at > now() - make_interval(mins => :'lookback_minutes'::integer)
      AND coalesce(params_snapshot->'effectiveParams'->>'channelCode', params_snapshot->>'channelCode') = :'channel_code'
      AND (nullif(:'batch_no', '') IS NULL OR
           coalesce(params_snapshot->'effectiveParams'->>'batchNo', params_snapshot->>'batchNo') = :'batch_no')
    ORDER BY created_at DESC LIMIT 1
), task_summary AS (
    SELECT latest.id AS instance_id, latest.instance_status, count(task.id) AS task_count,
           coalesce(string_agg(DISTINCT task.task_type, ',' ORDER BY task.task_type), '') AS task_types,
           coalesce(string_agg(DISTINCT task.task_status, ',' ORDER BY task.task_status), '') AS task_statuses
    FROM latest LEFT JOIN batch.job_task task
      ON task.tenant_id = latest.tenant_id AND task.job_instance_id = latest.id
    GROUP BY latest.id, latest.instance_status
), dispatch_summary AS (
    SELECT count(dispatch.id) AS dispatch_count,
           coalesce(string_agg(DISTINCT dispatch.dispatch_status, ',' ORDER BY dispatch.dispatch_status), '') AS dispatch_statuses
    FROM latest LEFT JOIN batch.file_dispatch_record dispatch
      ON dispatch.tenant_id = latest.tenant_id AND dispatch.channel_code = :'channel_code'
     AND dispatch.file_id::text = coalesce(latest.params_snapshot->'effectiveParams'->>'fileId', latest.params_snapshot->>'fileId')
)
SELECT task_summary.instance_id || '|' || coalesce(task_summary.instance_status, '') || '|' ||
       task_summary.task_count || '|' || task_summary.task_types || '|' || task_summary.task_statuses || '|' ||
       dispatch_summary.dispatch_count || '|' || dispatch_summary.dispatch_statuses
FROM task_summary CROSS JOIN dispatch_summary;
