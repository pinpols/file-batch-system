WITH scoped AS (
  SELECT *
  FROM batch.job_instance
  WHERE tenant_id = :'tenant_id'
    AND params_snapshot::text LIKE '%' || :'run_id' || '%'
)
SELECT
  i.id AS instance_id,
  i.job_code,
  i.instance_status,
  p.partition_status,
  t.task_status,
  coalesce(t.error_code, '') AS task_error_code,
  coalesce(t.assigned_worker_code, '') AS worker_code,
  round(extract(epoch FROM (i.finished_at - i.created_at))::numeric, 3) AS instance_seconds
FROM scoped i
LEFT JOIN batch.job_partition p ON p.job_instance_id = i.id
LEFT JOIN batch.job_task t ON t.job_partition_id = p.id
ORDER BY i.id, p.partition_no, t.task_seq;
