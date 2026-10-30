SELECT COUNT(*)
FROM :"schema".dead_letter_task
WHERE replay_status = 'NEW'
  AND (:'tenant' = '' OR tenant_id = :'tenant')
  AND (:'source_type' = '' OR source_type = :'source_type');
