SELECT tenant_id, job_code, instance_no, instance_status, updated_at
FROM :"schema".job_instance
WHERE instance_status IN ('RUNNING','PENDING')
  AND updated_at < NOW() - (:stuck_job_minutes::bigint * INTERVAL '1 minute')
ORDER BY updated_at ASC
LIMIT 10;
