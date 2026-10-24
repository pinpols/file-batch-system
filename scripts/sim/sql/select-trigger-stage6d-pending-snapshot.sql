SELECT jsonb_build_object(
    'pendingId', pending.id,
    'requestId', request.request_id,
    'originalFireTime', pending.scheduled_fire_time,
    'expiresAt', pending.expires_at,
    'pendingStatus', pending.status,
    'bizDate', request.biz_date,
    'requestStatus', request.request_status,
    'payloadFingerprint', md5(request.launch_payload::text),
    'occurrenceStatus', occurrence.occurrence_status,
    'occurrenceRequestId', occurrence.request_id,
    'workflowEnabled', definition.enabled,
    'outboxCount', (
        SELECT count(*) FROM batch.trigger_outbox_event outbox
        WHERE outbox.tenant_id = request.tenant_id
          AND outbox.request_id = request.request_id
    )
)::text
FROM batch.trigger_misfire_pending pending
JOIN batch.trigger_request request
  ON request.tenant_id = pending.tenant_id
 AND request.id = pending.catch_up_request_id
 AND request.workflow_definition_id = pending.workflow_definition_id
JOIN batch.scheduled_occurrence occurrence
  ON occurrence.tenant_id = pending.tenant_id
 AND occurrence.workflow_definition_id = pending.workflow_definition_id
 AND occurrence.scheduled_fire_time = pending.scheduled_fire_time
JOIN batch.workflow_definition definition
  ON definition.tenant_id = pending.tenant_id
 AND definition.id = pending.workflow_definition_id
WHERE pending.tenant_id = :'tenant_id'
  AND definition.workflow_code = 'WF-TA_TRIGGER_STAGE6C_MISFIRE'
  AND pending.scheduled_fire_time = to_timestamp(:'scheduled_fire_millis'::bigint / 1000.0)
  AND pending.created_at >= :'start_ts'::timestamptz
ORDER BY pending.id DESC LIMIT 1;
