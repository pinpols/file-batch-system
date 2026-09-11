SELECT instance.job_code,
       task.task_type,
       task.task_status,
       coalesce(task.error_code, '') AS error_code,
       count(*) AS count
FROM batch.job_instance instance
JOIN batch.job_task task ON task.job_instance_id = instance.id
WHERE instance.tenant_id = :'tenant_id'
  AND instance.params_snapshot::text LIKE '%' || :'run_id' || '%'
  AND instance.params_snapshot::text LIKE '%"stressUsers": ' || :'stress_users' || '%'
GROUP BY instance.job_code, task.task_type, task.task_status, task.error_code
ORDER BY instance.job_code, task.task_type, task.task_status, task.error_code;
