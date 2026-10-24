SELECT request.request_id,
       request.request_status,
       coalesce(workflow_run.run_status, ''),
       coalesce(string_agg(DISTINCT node_run.node_status, ','), ''),
       coalesce(string_agg(DISTINCT instance.instance_status, ','), ''),
       coalesce(string_agg(DISTINCT task.task_status, ','), '')
FROM batch.trigger_request request
LEFT JOIN batch.workflow_run workflow_run
  ON workflow_run.tenant_id = request.tenant_id
 AND workflow_run.trigger_request_id = request.id
LEFT JOIN batch.workflow_node_run node_run
  ON node_run.workflow_run_id = workflow_run.id
 AND node_run.node_type = 'JOB'
LEFT JOIN batch.job_instance instance
  ON instance.tenant_id = workflow_run.tenant_id
 AND instance.id = node_run.job_instance_id
LEFT JOIN batch.job_task task
  ON task.tenant_id = instance.tenant_id
 AND task.job_instance_id = instance.id
WHERE request.request_id = ANY(string_to_array(:'request_ids', ','))
GROUP BY request.request_id, request.request_status, workflow_run.run_status
ORDER BY request.request_id;
