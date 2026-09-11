SELECT partition_no, coalesce(source_file_id::text, ''), coalesce(template_code, ''), coalesce(target_ref, '')
FROM batch.job_partition
WHERE tenant_id = :'tenant_id' AND job_instance_id = :'instance_id'::bigint
ORDER BY partition_no;
