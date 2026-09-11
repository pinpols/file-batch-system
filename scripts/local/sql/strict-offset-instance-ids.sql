SELECT string_agg(id::text, ',' ORDER BY id DESC)
FROM (SELECT id FROM batch.job_instance WHERE tenant_id = :'tenant_id' ORDER BY id DESC LIMIT 1000) instances;
