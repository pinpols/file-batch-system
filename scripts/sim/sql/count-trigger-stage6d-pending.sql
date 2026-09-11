SELECT count(*) FROM batch.trigger_misfire_pending
WHERE tenant_id = :'tenant_id' AND job_code = 'TA_TRIGGER_STAGE6C_MISFIRE'
  AND status = 'PENDING' AND created_at >= :'start_ts'::timestamptz;
