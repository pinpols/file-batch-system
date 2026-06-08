SELECT tenant_id,
       sum((instance_status = 'SUCCESS')::int) AS succ,
       sum((instance_status = 'RUNNING')::int) AS run,
       sum((instance_status = 'FAILED')::int) AS fail,
       count(*) AS tot
FROM batch.job_instance
WHERE tenant_id IN ('ta','tb','tc','t04','t05','t06','t07','t08','t09','t10')
GROUP BY tenant_id
ORDER BY tenant_id;
