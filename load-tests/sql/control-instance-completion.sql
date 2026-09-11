WITH instances AS (
    SELECT
        coalesce(
            params_snapshot #>> '{requestParams,metadata,benchmarkModule}',
            params_snapshot #>> '{effectiveParams,metadata,benchmarkModule}',
            CASE
                WHEN job_code = 'lt_process_sql_job' THEN 'process'
                WHEN job_code = 'lt_dispatch_local_job' THEN 'dispatch'
                ELSE 'unknown'
            END
        ) AS benchmark_module,
        job_code,
        instance_status,
        created_at,
        finished_at
    FROM batch.job_instance
    WHERE tenant_id = :'tenant_id'
      AND params_snapshot::text LIKE '%' || :'run_id' || '%'
)
SELECT
    benchmark_module,
    job_code,
    count(*) AS total,
    count(*) FILTER (WHERE instance_status = 'SUCCESS') AS success,
    count(*) FILTER (WHERE instance_status = 'FAILED') AS failed,
    count(*) FILTER (
        WHERE instance_status NOT IN ('SUCCESS','FAILED','CANCELLED','TERMINATED','PARTIAL_FAILED')
    ) AS non_terminal,
    round(avg(extract(epoch FROM finished_at - created_at))
        FILTER (WHERE finished_at IS NOT NULL)::numeric, 3) AS avg_seconds,
    round(percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM finished_at - created_at))
        FILTER (WHERE finished_at IS NOT NULL)::numeric, 3) AS p95_seconds
FROM instances
GROUP BY benchmark_module, job_code
ORDER BY benchmark_module, job_code;
