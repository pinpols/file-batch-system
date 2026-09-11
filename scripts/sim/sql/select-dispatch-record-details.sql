SELECT channel_code,
       dispatch_status,
       receipt_status,
       dispatch_attempt,
       error_code,
       coalesce(error_message, '')
FROM batch.file_dispatch_record
WHERE tenant_id = :'tenant_id'
  AND file_id = :'file_id'::bigint
ORDER BY channel_code;
