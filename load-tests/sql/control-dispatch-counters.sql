SELECT dispatch.dispatch_status, count(*) AS count
FROM batch.file_dispatch_record dispatch
JOIN batch.file_record file
  ON file.tenant_id = dispatch.tenant_id
 AND file.id = dispatch.file_id
WHERE file.tenant_id = :'tenant_id'
  AND file.metadata_json::text LIKE '%' || :'run_id' || '%'
GROUP BY dispatch.dispatch_status
ORDER BY dispatch.dispatch_status;
