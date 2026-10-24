SELECT workflow_code
FROM batch.workflow_definition
WHERE tenant_id = :'tenant_id'
  AND id = :'workflow_id'::bigint
  AND enabled = TRUE;
