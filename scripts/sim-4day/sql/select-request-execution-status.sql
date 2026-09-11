SELECT coalesce(instance.instance_status, '') || '|'
       || coalesce((
           SELECT count(*)
           FROM batch.job_task task
           WHERE task.job_instance_id = instance.id
             AND task.task_status IN ('FAILED', 'PARTIAL_FAILED', 'CANCELLED', 'TERMINATED')
       ), 0) || '|'
       || coalesce((
           SELECT count(*)
           FROM batch.job_task task
           WHERE task.job_instance_id = instance.id
             AND task.task_status <> 'SUCCESS'
       ), 0)
FROM batch.trigger_request request
LEFT JOIN batch.job_instance instance ON instance.id = request.related_job_instance_id
WHERE request.tenant_id = :'tenant_id'
  AND request.request_id = :'request_id'
ORDER BY request.created_at DESC
LIMIT 1;
