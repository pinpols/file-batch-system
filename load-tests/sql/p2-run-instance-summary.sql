WITH scoped AS (
  SELECT DISTINCT ji.*
  FROM batch.job_instance ji
  LEFT JOIN batch.trigger_request tr ON tr.related_job_instance_id = ji.id
  WHERE ji.params_snapshot::text LIKE '%' || :'run_id' || '%'
     OR tr.request_id LIKE :'run_id' || '%'
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
