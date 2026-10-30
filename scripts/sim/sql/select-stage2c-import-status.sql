SELECT instance.id,
       instance.job_code,
       instance.instance_status,
       task.task_status,
       task.error_code,
       left(coalesce(task.error_message, ''), 160) AS error_message
FROM batch.trigger_request request
JOIN batch.workflow_run workflow_run
  ON workflow_run.tenant_id = request.tenant_id
 AND workflow_run.trigger_request_id = request.id
JOIN batch.workflow_node_run node_run
  ON node_run.workflow_run_id = workflow_run.id
JOIN batch.job_instance instance
  ON instance.tenant_id = workflow_run.tenant_id
 AND instance.id = node_run.job_instance_id
LEFT JOIN batch.job_task task ON task.job_instance_id = instance.id
WHERE request.tenant_id = :'tenant_id'
  AND request.request_id = ANY(string_to_array(:'request_ids', ','))
ORDER BY instance.created_at, instance.id, node_run.id, task.id;
