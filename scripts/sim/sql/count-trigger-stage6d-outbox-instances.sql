SELECT count(DISTINCT node_run.job_instance_id)
FROM batch.trigger_request request
JOIN batch.workflow_run workflow_run ON workflow_run.tenant_id = request.tenant_id
  AND workflow_run.trigger_request_id = request.id
JOIN batch.workflow_node_run node_run ON node_run.workflow_run_id = workflow_run.id
WHERE request.tenant_id = :'tenant_id' AND request.request_id LIKE :'request_prefix' || '%'
  AND node_run.job_instance_id IS NOT NULL;
