SELECT count(*)
FROM batch.trigger_request request
JOIN batch.job_instance instance ON instance.id = request.related_job_instance_id
JOIN batch.job_task task ON task.job_instance_id = instance.id
WHERE request.tenant_id = :'tenant_id'
  AND request.request_id = ANY(string_to_array(:'request_ids', ','))
  AND instance.instance_status = 'SUCCESS'
  AND task.task_status = 'SUCCESS';
