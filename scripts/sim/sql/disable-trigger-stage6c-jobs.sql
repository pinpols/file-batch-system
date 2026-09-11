UPDATE batch.job_definition
SET enabled = false,
    updated_by = 'sim-cleanup',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'ta'
  AND job_code IN ('TA_TRIGGER_STAGE6C_SCHEDULED', 'TA_TRIGGER_STAGE6C_MISFIRE');
