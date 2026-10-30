SELECT count(*) AS trigger_rows,
       count(DISTINCT node_run.job_instance_id) AS job_instances
FROM batch.trigger_request request
LEFT JOIN batch.workflow_run workflow_run
  ON workflow_run.tenant_id = request.tenant_id
 AND workflow_run.trigger_request_id = request.id
LEFT JOIN batch.workflow_node_run node_run
  ON node_run.workflow_run_id = workflow_run.id
 AND node_run.job_instance_id IS NOT NULL
WHERE request.tenant_id = :'tenant_id'
  AND request.request_id = :'request_id';
