SELECT coalesce(instance.id::text, ''),
       coalesce(partition.id::text, ''),
       coalesce(pipeline.id::text, ''),
       coalesce(progress.position_marker, '0'),
       coalesce(progress.processed_count::text, '0'),
       coalesce(progress.completed::text, 'false'),
       coalesce(instance.instance_status, ''),
       coalesce(partition.partition_status, ''),
       coalesce(request.request_status, ''),
       coalesce(outbox.publish_status, ''),
       left(coalesce(outbox.last_error, ''), 180),
       coalesce(task.task_status, ''),
       coalesce(partition.lease_expire_at::text, '')
FROM batch.trigger_request request
LEFT JOIN batch.job_instance instance ON instance.id = request.related_job_instance_id
LEFT JOIN batch.job_partition partition ON partition.job_instance_id = instance.id
LEFT JOIN batch.job_task task ON task.job_partition_id = partition.id
LEFT JOIN batch.pipeline_instance pipeline ON pipeline.related_job_instance_id = instance.id
LEFT JOIN batch.pipeline_progress progress ON progress.pipeline_instance_id = pipeline.id
    AND progress.stage = 'LOAD'
    AND progress.updated_at >= request.created_at
LEFT JOIN batch.trigger_outbox_event outbox ON outbox.tenant_id = request.tenant_id
    AND outbox.request_id = request.request_id
WHERE request.tenant_id = :'tenant_id'
  AND request.request_id = :'request_id'
ORDER BY outbox.id DESC NULLS LAST, partition.id DESC
LIMIT 1;
