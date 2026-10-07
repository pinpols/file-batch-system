SELECT p.partition_no, coalesce(p.source_file_id::text, ''), coalesce(p.template_code, ''),
       coalesce(p.target_ref, ''), coalesce(f.storage_path, '')
FROM batch.job_partition p
LEFT JOIN batch.file_record f ON f.id = p.source_file_id AND f.tenant_id = p.tenant_id
WHERE p.tenant_id = :'tenant_id' AND p.job_instance_id = :'instance_id'::bigint
ORDER BY p.partition_no;
