UPDATE batch.workflow_definition
SET schedule_enabled = false,
    updated_by = 'sim-cleanup',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'ta'
  AND workflow_code IN ('WF-TA_TRIGGER_STAGE6C_SCHEDULED', 'WF-TA_TRIGGER_STAGE6C_MISFIRE');
