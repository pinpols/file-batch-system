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
    task.task_type,
    count(*) AS tasks,
    count(*) FILTER (WHERE task.task_status = 'SUCCESS') AS success,
    count(*) FILTER (WHERE task.task_status = 'FAILED') AS failed,
    round(avg(extract(epoch FROM task.started_at - task.created_at))
        FILTER (WHERE task.started_at IS NOT NULL)::numeric, 3) AS avg_claim_delay_s,
    round(percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM task.started_at - task.created_at))
        FILTER (WHERE task.started_at IS NOT NULL)::numeric, 3) AS p95_claim_delay_s,
    round(avg(extract(epoch FROM task.finished_at - task.started_at))
        FILTER (WHERE task.finished_at IS NOT NULL AND task.started_at IS NOT NULL)::numeric, 3) AS avg_exec_s,
    round(percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM task.finished_at - task.started_at))
        FILTER (WHERE task.finished_at IS NOT NULL AND task.started_at IS NOT NULL)::numeric, 3) AS p95_exec_s
FROM instances instance
JOIN batch.job_task task
  ON task.tenant_id = instance.tenant_id
 AND task.job_instance_id = instance.id
GROUP BY instance.benchmark_module, instance.job_code, task.task_type
ORDER BY instance.benchmark_module, instance.job_code, task.task_type;
