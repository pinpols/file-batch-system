SELECT instance.job_code, step.stage_code, count(*) AS runs,
       round(avg(step.duration_ms)::numeric, 1) AS avg_ms,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY step.duration_ms)::numeric, 1) AS p95_ms,
       max(step.duration_ms) AS max_ms
FROM batch.job_instance instance
JOIN batch.pipeline_instance pipeline ON pipeline.tenant_id = instance.tenant_id AND pipeline.related_job_instance_id = instance.id
JOIN batch.pipeline_step_run step ON step.pipeline_instance_id = pipeline.id
WHERE instance.tenant_id = :'tenant_id' AND instance.job_code IN ('lt_process_sql_job','lt_process_copy_job')
  AND instance.params_snapshot::text LIKE '%' || :'run_id' || '%'
GROUP BY instance.job_code, step.stage_code ORDER BY instance.job_code, step.stage_code;
