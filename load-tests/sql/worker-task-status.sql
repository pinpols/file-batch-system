SELECT instance.job_code, task.task_type, task.task_status, count(*) AS count
FROM batch.job_instance instance
JOIN batch.job_task task ON task.tenant_id = instance.tenant_id AND task.job_instance_id = instance.id
WHERE instance.tenant_id = :'tenant_id' AND instance.params_snapshot::text LIKE '%' || :'run_id' || '%'
  AND instance.job_code IN ('import_customer_job','export_settlement_job','lt_dispatch_local_job','lt_process_sql_job')
GROUP BY instance.job_code, task.task_type, task.task_status
ORDER BY instance.job_code, task.task_type, task.task_status;
