SELECT tenant_id, count(*) AS jobs
FROM batch.job_definition
WHERE tenant_id IN ('ta','tb','tc')
GROUP BY tenant_id;
