DELETE FROM batch.workflow_node
WHERE tenant_id = :'tenant_id'
  AND workflow_definition_id = :'workflow_definition_id'::bigint
  AND node_code = 'SEEDVAL_PROBE';
