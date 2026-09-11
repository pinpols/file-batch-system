SELECT string_agg(
    DISTINCT coalesce(error_code, '') || ':' || coalesce(error_message, ''),
    ' | '
)
FROM batch.job_task
WHERE tenant_id = :'tenant_id'
  AND job_instance_id = :'instance_id'::bigint
  AND error_code IS NOT NULL;
