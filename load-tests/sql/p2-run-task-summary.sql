WITH scoped AS (
  SELECT DISTINCT ji.id, ji.tenant_id, ji.job_code
  FROM batch.job_instance ji
  LEFT JOIN batch.trigger_request tr ON tr.related_job_instance_id = ji.id
  WHERE ji.params_snapshot::text LIKE '%' || :'run_id' || '%'
     OR tr.request_id LIKE :'run_id' || '%'
)
SELECT
  i.tenant_id,
  i.job_code,
  t.task_type,
  t.task_status,
  coalesce(t.error_code, '') AS error_code,
  count(*) AS tasks,
  round(avg(extract(epoch FROM (t.started_at - t.created_at))) FILTER (WHERE t.started_at IS NOT NULL)::numeric, 3) AS avg_claim_delay_s,
  round(percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM (t.started_at - t.created_at))) FILTER (WHERE t.started_at IS NOT NULL)::numeric, 3) AS p95_claim_delay_s
FROM scoped i
JOIN batch.job_task t ON t.job_instance_id = i.id
GROUP BY i.tenant_id, i.job_code, t.task_type, t.task_status, t.error_code
ORDER BY i.tenant_id, i.job_code, t.task_type, t.task_status, t.error_code;
