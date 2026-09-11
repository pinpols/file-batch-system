SELECT count(*)
FROM batch.workflow_node
WHERE tenant_id = :'tenant_id';
