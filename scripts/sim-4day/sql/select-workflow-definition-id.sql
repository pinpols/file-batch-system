SELECT id
FROM batch.workflow_definition
WHERE tenant_id = :'tenant_id'
  AND workflow_code = :'workflow_code'
  AND enabled = TRUE
ORDER BY version DESC
LIMIT 1;
