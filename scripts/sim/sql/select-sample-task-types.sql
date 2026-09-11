SELECT task_type_code
FROM batch.custom_task_type_registry
WHERE tenant_id = :'tenant_id'
  AND task_type_code LIKE 'sample_%';
