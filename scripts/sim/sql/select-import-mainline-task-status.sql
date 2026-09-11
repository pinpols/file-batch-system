SELECT coalesce(tenant_id, '') || '/' || coalesce(task_type, '') || '/'
       || coalesce(task_status, '') || '/' || coalesce(error_code, '') || '/'
       || left(coalesce(error_message, ''), 160)
FROM batch.job_task
WHERE job_instance_id IN (
    SELECT id
    FROM batch.job_instance
    WHERE dedup_key = ANY(string_to_array(:'request_ids', ','))
)
ORDER BY id;
