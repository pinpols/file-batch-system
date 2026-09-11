SELECT biz_type,
       file_format_type,
       file_status,
       count(*) AS files,
       sum(file_size_bytes) AS bytes
FROM batch.file_record
WHERE tenant_id = :'tenant_id'
  AND created_at >= :'start_ts'::timestamptz
  AND source_type = 'GENERATED'
GROUP BY biz_type, file_format_type, file_status
ORDER BY biz_type, file_format_type, file_status;
