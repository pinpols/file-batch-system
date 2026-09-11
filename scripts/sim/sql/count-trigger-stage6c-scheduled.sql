SELECT count(*) FROM batch.trigger_request
WHERE tenant_id = :'tenant_id' AND job_code = 'TA_TRIGGER_STAGE6C_SCHEDULED'
  AND trigger_type = 'SCHEDULED' AND created_at >= :'start_ts'::timestamptz;
