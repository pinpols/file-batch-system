SELECT tenant_id, source_type, COUNT(*) AS cnt
FROM :"schema".dead_letter_task
WHERE replay_status = 'NEW'
  AND (:'tenant' = '' OR tenant_id = :'tenant')
  AND (:'source_type' = '' OR source_type = :'source_type')
GROUP BY tenant_id, source_type
ORDER BY cnt DESC
LIMIT 20;
