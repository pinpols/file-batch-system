SELECT count(*) FROM batch.trigger_outbox_event
WHERE tenant_id = :'tenant_id' AND request_id LIKE :'request_prefix' || '%';
