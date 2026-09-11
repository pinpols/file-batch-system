SELECT coalesce(max(id), 0) FROM batch.job_instance WHERE tenant_id = :'tenant_id';
