SELECT count(*) FROM batch.trigger_request
WHERE tenant_id = :'tenant_id'
  AND workflow_definition_id = (
      SELECT id FROM batch.workflow_definition
      WHERE tenant_id = :'tenant_id' AND workflow_code = 'WF-TA_TRIGGER_STAGE6C_SCHEDULED'
      ORDER BY version DESC LIMIT 1
  )
  AND trigger_type = 'SCHEDULED' AND created_at >= :'start_ts'::timestamptz;
