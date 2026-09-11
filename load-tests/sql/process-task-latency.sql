SELECT instance.job_code, task.task_status, count(*) AS tasks,
       round(avg(extract(epoch FROM task.started_at - task.created_at)) FILTER (WHERE task.started_at IS NOT NULL)::numeric, 3) AS avg_claim_delay_s,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM task.started_at - task.created_at)) FILTER (WHERE task.started_at IS NOT NULL)::numeric, 3) AS p95_claim_delay_s,
       round(avg(extract(epoch FROM task.finished_at - task.started_at)) FILTER (WHERE task.finished_at IS NOT NULL AND task.started_at IS NOT NULL)::numeric, 3) AS avg_exec_s,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM task.finished_at - task.started_at)) FILTER (WHERE task.finished_at IS NOT NULL AND task.started_at IS NOT NULL)::numeric, 3) AS p95_exec_s
FROM batch.job_instance instance
JOIN batch.job_task task ON task.tenant_id = instance.tenant_id AND task.job_instance_id = instance.id
WHERE instance.tenant_id = :'tenant_id' AND instance.job_code IN ('lt_process_sql_job','lt_process_copy_job')
  AND instance.params_snapshot::text LIKE '%' || :'run_id' || '%'
GROUP BY instance.job_code, task.task_status ORDER BY instance.job_code, task.task_status;
