SELECT count(*)
FROM batch.trigger_request request
LEFT JOIN batch.trigger_outbox_event event
  ON event.tenant_id = request.tenant_id
 AND event.request_id = request.request_id
LEFT JOIN batch.workflow_run workflow_run
  ON workflow_run.tenant_id = request.tenant_id
 AND workflow_run.trigger_request_id = request.id
WHERE request.tenant_id = :'tenant_id'
  AND request.dedup_key = :'idempotency_key'
  AND request.request_status NOT IN ('REJECTED', 'DUPLICATE')
  AND workflow_run.id IS NULL
  AND COALESCE(event.publish_status, 'NEW') <> 'GIVE_UP';
