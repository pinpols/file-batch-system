SELECT biz_type, storage_path, file_ext, file_size_bytes
FROM batch.file_record
WHERE tenant_id = :'tenant_id'
  AND created_at >= :'start_ts'::timestamptz
  AND source_type = 'GENERATED'
ORDER BY created_at;
