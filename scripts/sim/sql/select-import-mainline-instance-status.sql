SELECT tenant_id || '/' || job_code || '|' || instance_status || '|' || id || '|' || instance_no
FROM batch.job_instance
WHERE dedup_key = ANY(string_to_array(:'request_ids', ','))
ORDER BY tenant_id, job_code;
