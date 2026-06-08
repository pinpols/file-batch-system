SELECT id || '|' || tenant_id
FROM :"schema".dead_letter_task
WHERE replay_status = 'NEW'
  AND (:'tenant' = '' OR tenant_id = :'tenant')
  AND (:'source_type' = '' OR source_type = :'source_type')
ORDER BY created_at ASC
LIMIT :batch_size OFFSET :batch_offset;
