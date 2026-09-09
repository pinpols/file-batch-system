SELECT coalesce(string_agg(tenant_id || '/' || event_key || ':' || duplicate_count, ', '), '')
FROM (
    SELECT tenant_id, event_key, count(*) AS duplicate_count
    FROM batch.outbox_event
    GROUP BY tenant_id, event_key
    HAVING count(*) > 1
) duplicates;
