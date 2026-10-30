SELECT instance.instance_status || '|' || task.task_status || '|'
       || coalesce(instance.params_snapshot #>> '{effectiveParams,command}', '') || '|'
       || coalesce(instance.params_snapshot #>> '{effectiveParams,args,0}', '') || '|'
       || coalesce(instance.params_snapshot #>> '{executionPolicy,retryPolicy}', '') || '|'
       || coalesce(instance.params_snapshot #>> '{executionPolicy,retryMaxCount}', '') || '|'
       || coalesce(instance.params_snapshot #>> '{executionPolicy,timeoutSeconds}', '') || '|'
       || coalesce(task.task_payload ->> 'command', '') || '|'
       || coalesce(task.task_payload #>> '{args,0}', '')
FROM batch.trigger_request request
JOIN batch.workflow_run workflow_run
  ON workflow_run.tenant_id = request.tenant_id
 AND workflow_run.trigger_request_id = request.id
JOIN batch.workflow_node_run node_run ON node_run.workflow_run_id = workflow_run.id
JOIN batch.job_instance instance
  ON instance.tenant_id = workflow_run.tenant_id
 AND instance.id = node_run.job_instance_id
JOIN batch.job_task task
  ON task.job_instance_id = instance.id
WHERE request.tenant_id = :'tenant_id'
  AND request.request_id = :'request_id'
ORDER BY node_run.id DESC, task.id DESC
LIMIT 1;
