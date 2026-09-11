SELECT id
FROM batch.file_record
WHERE tenant_id = :'tenant_id'
  AND file_category = 'OUTPUT'
  AND source_type = 'GENERATED'
  AND source_ref = :'batch_no'
  AND file_status IN ('GENERATED', 'DISPATCHED')
ORDER BY created_at DESC
LIMIT 1;
