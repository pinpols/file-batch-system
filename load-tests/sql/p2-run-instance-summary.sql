WITH scoped AS (
  SELECT DISTINCT ji.*
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
  tenant_id,
  job_code,
  count(*) AS total,
  count(*) FILTER (WHERE instance_status = 'SUCCESS') AS success,
  count(*) FILTER (WHERE instance_status = 'FAILED') AS failed,
  count(*) FILTER (
    WHERE instance_status NOT IN ('SUCCESS','FAILED','PARTIAL_FAILED','CANCELLED','TERMINATED','REJECTED')
  ) AS non_terminal,
  round(avg(extract(epoch FROM (finished_at - created_at))) FILTER (WHERE finished_at IS NOT NULL)::numeric, 3) AS avg_seconds,
  round(percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM (finished_at - created_at))) FILTER (WHERE finished_at IS NOT NULL)::numeric, 3) AS p95_seconds
FROM scoped
GROUP BY tenant_id, job_code
ORDER BY tenant_id, job_code;
