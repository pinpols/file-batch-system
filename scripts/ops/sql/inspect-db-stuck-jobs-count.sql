SELECT COUNT(*)
FROM :"schema".job_instance
WHERE instance_status IN ('RUNNING','PENDING')
  AND updated_at < NOW() - (:stuck_job_minutes::bigint * INTERVAL '1 minute');
