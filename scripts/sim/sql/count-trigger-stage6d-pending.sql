SELECT count(*) FROM batch.trigger_misfire_pending
WHERE tenant_id = :'tenant_id'
  AND workflow_definition_id = (
      SELECT id FROM batch.workflow_definition
      WHERE tenant_id = :'tenant_id' AND workflow_code = 'WF-TA_TRIGGER_STAGE6C_MISFIRE'
      ORDER BY version DESC LIMIT 1
  )
  AND scheduled_fire_time = to_timestamp(:'scheduled_fire_millis'::bigint / 1000.0)
  AND status = 'PENDING' AND created_at >= :'start_ts'::timestamptz;
