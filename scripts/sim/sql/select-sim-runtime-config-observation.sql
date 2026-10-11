SELECT instance.instance_status || '|' || task.task_status || '|'
       || coalesce(instance.params_snapshot #>> '{effectiveParams,command}', '') || '|'
       || coalesce(instance.params_snapshot #>> '{effectiveParams,args,0}', '') || '|'
       || coalesce(instance.params_snapshot #>> '{executionPolicy,retryPolicy}', '') || '|'
       || coalesce(instance.params_snapshot #>> '{executionPolicy,retryMaxCount}', '') || '|'
       || coalesce(instance.params_snapshot #>> '{executionPolicy,timeoutSeconds}', '') || '|'
       || coalesce(task.task_payload ->> 'command', '') || '|'
       || coalesce(task.task_payload #>> '{args,0}', '')
FROM batch.trigger_request request
JOIN batch.job_instance instance
  ON instance.id = request.related_job_instance_id
JOIN batch.job_task task
  ON task.job_instance_id = instance.id
WHERE request.tenant_id = :'tenant_id'
  AND request.request_id = :'request_id'
ORDER BY task.id DESC
LIMIT 1;
