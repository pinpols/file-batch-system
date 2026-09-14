SELECT string_agg(id::text, ',' ORDER BY file_code)
FROM batch.file_record
WHERE tenant_id = 'default-tenant'
  AND file_code LIKE :'run_id' || '-DISPATCH-FILE-%';
