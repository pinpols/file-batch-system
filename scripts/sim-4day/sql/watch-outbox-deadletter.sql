SELECT 'outbox_pending=' || count(*)
FROM batch.outbox_event
WHERE publish_status <> 'PUBLISHED'
UNION ALL
SELECT 'dead_letter=' || count(*)
FROM batch.dead_letter_task;
