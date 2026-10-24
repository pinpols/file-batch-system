SELECT chunk_size
FROM batch.file_template_config
WHERE tenant_id = :'tenant_id'
  AND template_code = :'template_code'
  AND is_deleted = false;
