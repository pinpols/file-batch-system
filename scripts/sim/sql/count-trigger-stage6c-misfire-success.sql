SELECT count(*) FROM batch.job_instance
WHERE tenant_id = :'tenant_id' AND job_code = 'TA_TRIGGER_STAGE6C_MISFIRE'
  AND instance_status = 'SUCCESS' AND created_at >= :'start_ts'::timestamptz;
