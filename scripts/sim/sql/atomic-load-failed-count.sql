SELECT count(*)
FROM batch.trigger_request tr
LEFT JOIN batch.job_instance i ON i.id = tr.related_job_instance_id
LEFT JOIN batch.job_task t ON t.job_instance_id = i.id
WHERE tr.tenant_id = :'tenant_id'
  AND tr.request_id = ANY (string_to_array(:'request_ids_csv', ','))
  AND (
      i.instance_status <> 'SUCCESS'
      OR coalesce(t.task_status, '') <> 'SUCCESS'
  );
