SELECT 'dispatch_records' AS metric, count(*)::text AS value
FROM batch.file_dispatch_record dispatch
JOIN batch.file_record file ON file.tenant_id = dispatch.tenant_id AND file.id = dispatch.file_id
WHERE file.tenant_id = :'tenant_id' AND file.metadata_json::text LIKE '%' || :'run_id' || '%'
UNION ALL SELECT 'dispatch_files_dispatched', count(*)::text FROM batch.file_record
WHERE tenant_id = :'tenant_id' AND metadata_json::text LIKE '%' || :'run_id' || '%' AND file_status = 'DISPATCHED';
