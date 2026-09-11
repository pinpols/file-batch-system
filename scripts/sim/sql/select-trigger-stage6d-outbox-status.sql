SELECT publish_status, count(*) FROM batch.trigger_outbox_event
WHERE tenant_id = :'tenant_id' AND request_id LIKE :'request_prefix' || '%'
GROUP BY publish_status ORDER BY publish_status;
