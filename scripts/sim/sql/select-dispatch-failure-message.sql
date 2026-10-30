SELECT dispatch.error_message
FROM batch.file_dispatch_record dispatch
JOIN batch.job_instance instance ON instance.tenant_id = dispatch.tenant_id
WHERE instance.tenant_id = :'tenant_id'
  AND instance.id = :'instance_id'::bigint
  AND dispatch.file_id = :'file_id'::bigint
  AND dispatch.channel_code = :'channel_code';
