UPDATE batch.pipeline_instance
SET run_status = 'FAILED',
    finished_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
WHERE run_status = 'RUNNING'
  AND started_at IS NOT NULL
  AND started_at < CURRENT_TIMESTAMP - (:max_age_seconds::bigint * INTERVAL '1 second')
  AND (:'tenant' = '' OR tenant_id = :'tenant')
RETURNING id;
