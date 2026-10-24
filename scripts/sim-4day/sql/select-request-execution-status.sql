SELECT coalesce(workflow_run.run_status, '') || '|'
       || coalesce((
           SELECT count(*)
           FROM batch.job_task task
           WHERE task.job_instance_id = node_run.job_instance_id
             AND task.task_status IN ('FAILED', 'PARTIAL_FAILED', 'CANCELLED', 'TERMINATED')
       ), 0) || '|'
       || coalesce((
           SELECT count(*)
           FROM batch.job_task task
           WHERE task.job_instance_id = node_run.job_instance_id
             AND task.task_status <> 'SUCCESS'
       ), 0)
FROM batch.trigger_request request
JOIN batch.workflow_run workflow_run
  ON workflow_run.tenant_id = request.tenant_id
 AND workflow_run.trigger_request_id = request.id
LEFT JOIN batch.workflow_node_run node_run
  ON node_run.workflow_run_id = workflow_run.id
WHERE request.tenant_id = :'tenant_id'
  AND request.request_id = :'request_id'
ORDER BY request.created_at DESC
LIMIT 1;
