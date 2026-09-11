SELECT request_status
FROM batch.trigger_request
WHERE tenant_id = :'tenant_id'
  AND request_id = :'request_id';
