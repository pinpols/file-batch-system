SELECT id, biz_type, file_status, file_format_type, file_size_bytes, created_at
FROM batch.file_record
WHERE tenant_id = :'tenant_id'
  AND created_at >= :'start_ts'::timestamptz
  AND biz_type IN ('TA_IMPORT_CUSTOMER_XML', 'TA_IMPORT_CUSTOMER_FIXED')
ORDER BY created_at;
