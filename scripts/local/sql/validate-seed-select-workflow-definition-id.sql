SELECT id
FROM batch.workflow_definition
WHERE tenant_id = :'tenant_id'
  AND workflow_code = :'workflow_code'
LIMIT 1;
