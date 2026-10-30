WITH scoped AS (
  SELECT DISTINCT ji.id, ji.tenant_id, ji.job_code
  FROM batch.job_instance ji
  LEFT JOIN batch.trigger_request node_request ON node_request.id = ji.trigger_request_id
  LEFT JOIN batch.workflow_node_run node_run ON node_run.job_instance_id = ji.id
  LEFT JOIN batch.workflow_run workflow_run ON workflow_run.id = node_run.workflow_run_id
  LEFT JOIN batch.trigger_request root_request
    ON root_request.id = workflow_run.trigger_request_id
  WHERE coalesce(ji.params_snapshot #>> '{requestParams,metadata,runId}', ji.params_snapshot #>> '{effectiveParams,metadata,runId}', ji.params_snapshot #>> '{metadata,runId}', ji.params_snapshot #>> '{runId}') = :'run_id'
     OR node_request.request_id LIKE :'run_id' || '%'
     OR root_request.request_id LIKE :'run_id' || '%'
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
