WITH scoped AS (
    SELECT job_code, instance_status, created_at, finished_at FROM batch.job_instance
    WHERE tenant_id = :'tenant_id'
      AND job_code IN ('import_customer_job','export_settlement_job','lt_dispatch_local_job','lt_process_sql_job')
      AND params_snapshot::text LIKE '%' || :'run_id' || '%'
)
SELECT job_code, count(*) AS total,
       count(*) FILTER (WHERE instance_status = 'SUCCESS') AS success,
       count(*) FILTER (WHERE instance_status = 'FAILED') AS failed,
       count(*) FILTER (WHERE instance_status NOT IN ('SUCCESS','FAILED','CANCELLED','TERMINATED','PARTIAL_FAILED')) AS non_terminal,
       round(avg(extract(epoch FROM finished_at - created_at)) FILTER (WHERE finished_at IS NOT NULL)::numeric, 3) AS avg_seconds,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM finished_at - created_at)) FILTER (WHERE finished_at IS NOT NULL)::numeric, 3) AS p95_seconds
FROM scoped GROUP BY job_code ORDER BY job_code;
