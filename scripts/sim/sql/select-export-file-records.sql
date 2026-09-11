SELECT file_name,
       file_status,
       file_size_bytes,
       metadata_json->>'recordCount' AS record_count,
       storage_path
FROM batch.file_record
WHERE tenant_id = :'tenant_id'
  AND source_ref = :'batch_no'
  AND source_type = 'GENERATED'
ORDER BY file_name;
