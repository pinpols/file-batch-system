WITH instances AS (
    SELECT
        tenant_id,
        id,
        coalesce(
            params_snapshot #>> '{requestParams,metadata,benchmarkModule}',
            params_snapshot #>> '{effectiveParams,metadata,benchmarkModule}',
            CASE
                WHEN job_code = 'lt_process_sql_job' THEN 'process'
                WHEN job_code = 'lt_dispatch_local_job' THEN 'dispatch'
                ELSE 'unknown'
            END
        ) AS benchmark_module,
        job_code
    FROM batch.job_instance
    WHERE tenant_id = :'tenant_id'
      AND params_snapshot::text LIKE '%' || :'run_id' || '%'
)
SELECT
    instance.benchmark_module,
    instance.job_code,
    step.stage_code,
    count(*) AS runs,
    round(avg(step.duration_ms)::numeric, 1) AS avg_ms,
    round(percentile_cont(0.95) WITHIN GROUP (ORDER BY step.duration_ms)::numeric, 1) AS p95_ms,
    max(step.duration_ms) AS max_ms
FROM instances instance
JOIN batch.pipeline_instance pipeline
  ON pipeline.tenant_id = instance.tenant_id
 AND pipeline.related_job_instance_id = instance.id
JOIN batch.pipeline_step_run step ON step.pipeline_instance_id = pipeline.id
GROUP BY instance.benchmark_module, instance.job_code, step.stage_code
ORDER BY instance.benchmark_module, instance.job_code, step.stage_code;
