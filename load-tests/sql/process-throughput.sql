WITH stage AS (
    SELECT instance.job_code, step.stage_code, avg(step.duration_ms) / 1000.0 AS avg_seconds
    FROM batch.job_instance instance
    JOIN batch.pipeline_instance pipeline ON pipeline.tenant_id = instance.tenant_id AND pipeline.related_job_instance_id = instance.id
    JOIN batch.pipeline_step_run step ON step.pipeline_instance_id = pipeline.id
    WHERE instance.tenant_id = :'tenant_id' AND instance.job_code IN ('lt_process_sql_job','lt_process_copy_job')
      AND instance.params_snapshot::text LIKE '%' || :'run_id' || '%' AND step.stage_code IN ('COMPUTE','COMMIT')
    GROUP BY instance.job_code, step.stage_code
)
SELECT job_code, stage_code, round(avg_seconds::numeric, 3) AS avg_seconds,
       CASE WHEN job_code = 'lt_process_sql_job' AND stage_code = 'COMMIT'
            THEN round((:'account_count'::numeric / nullif(avg_seconds, 0))::numeric, 1)
            ELSE round((:'source_rows'::numeric / nullif(avg_seconds, 0))::numeric, 1) END AS estimated_rows_per_second
FROM stage ORDER BY job_code, stage_code;
