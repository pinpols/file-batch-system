SELECT id
FROM batch.file_record
WHERE tenant_id = :'tenant_id'
  AND biz_date = :'biz_date'::date
  AND file_status = 'GENERATED'
  AND file_size_bytes > 0
  AND storage_path LIKE 'outbound/' || :'export_job' || '/%'
ORDER BY id DESC
LIMIT 1;
