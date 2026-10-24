SELECT pending.id || '|' || coalesce(request.request_id, '')
FROM batch.trigger_misfire_pending pending
LEFT JOIN batch.trigger_request request
  ON request.tenant_id = pending.tenant_id
 AND request.id = pending.catch_up_request_id
 AND request.workflow_definition_id = pending.workflow_definition_id
WHERE pending.tenant_id = :'tenant_id'
  AND pending.workflow_definition_id = (
      SELECT id FROM batch.workflow_definition
      WHERE tenant_id = :'tenant_id' AND workflow_code = 'WF-TA_TRIGGER_STAGE6C_MISFIRE'
      ORDER BY version DESC LIMIT 1
  )
  AND pending.scheduled_fire_time = to_timestamp(:'scheduled_fire_millis'::bigint / 1000.0)
  AND pending.status = 'PENDING' AND pending.created_at >= :'start_ts'::timestamptz
ORDER BY pending.id DESC LIMIT 1;
