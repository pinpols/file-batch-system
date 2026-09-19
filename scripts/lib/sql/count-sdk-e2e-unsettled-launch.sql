SELECT count(*)
FROM batch.trigger_request request
LEFT JOIN batch.trigger_outbox_event event
  ON event.tenant_id = request.tenant_id
 AND event.request_id = request.request_id
WHERE request.tenant_id = :'tenant_id'
  AND request.dedup_key = :'idempotency_key'
  AND request.related_job_instance_id IS NULL
  AND request.request_status NOT IN ('REJECTED', 'DUPLICATE')
  AND COALESCE(event.publish_status, 'NEW') <> 'GIVE_UP';
