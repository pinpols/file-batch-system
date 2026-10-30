SELECT id FROM batch.trigger_misfire_pending
WHERE tenant_id = :'tenant_id'
  AND workflow_definition_id = (
      SELECT id FROM batch.workflow_definition
      WHERE tenant_id = :'tenant_id' AND workflow_code = 'WF-TA_TRIGGER_STAGE6C_MISFIRE'
      ORDER BY version DESC LIMIT 1
  )
  AND status = 'PENDING'
ORDER BY id DESC LIMIT 1;
