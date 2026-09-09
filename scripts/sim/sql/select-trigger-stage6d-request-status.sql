SELECT trigger_type, request_status, count(*) FROM batch.trigger_request
WHERE tenant_id = :'tenant_id' AND request_id LIKE :'request_prefix' || '%'
GROUP BY trigger_type, request_status ORDER BY trigger_type, request_status;
