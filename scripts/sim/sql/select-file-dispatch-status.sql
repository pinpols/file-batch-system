SELECT coalesce(dispatch_status, '')
FROM batch.file_dispatch_record
WHERE tenant_id = :'tenant_id'
  AND file_id = :'file_id'::bigint
  AND channel_code = :'channel_code'
ORDER BY created_at DESC
LIMIT 1;
