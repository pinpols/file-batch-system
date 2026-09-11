SELECT channel_code || ':' || dispatch_status || ':' || receipt_status
FROM batch.file_dispatch_record
WHERE tenant_id = :'tenant_id'
  AND file_id = :'file_id'::bigint
ORDER BY channel_code;
