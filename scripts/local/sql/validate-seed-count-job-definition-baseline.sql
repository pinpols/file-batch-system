SELECT count(*)
FROM batch.job_definition
WHERE tenant_id IN (:'tenant_id', :'secondary_tenant_id');
