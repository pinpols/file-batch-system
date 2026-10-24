SELECT i.id
FROM batch.trigger_request r
JOIN batch.workflow_run workflow_run ON workflow_run.tenant_id = r.tenant_id
  AND workflow_run.trigger_request_id = r.id
JOIN batch.workflow_node_run node_run ON node_run.workflow_run_id = workflow_run.id
JOIN batch.job_instance i ON i.id = node_run.job_instance_id AND i.tenant_id = workflow_run.tenant_id
WHERE r.tenant_id = :'tenant_id' AND r.request_id = :'request_id'
  AND i.job_code = :'job_code' AND i.id > :'after_id'::bigint
ORDER BY i.id DESC LIMIT 1;
