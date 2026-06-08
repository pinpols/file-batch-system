SELECT id
FROM batch.file_record
WHERE tenant_id = 'default-tenant'
  AND file_code = :'run_id' || '-DISPATCH-FILE';
