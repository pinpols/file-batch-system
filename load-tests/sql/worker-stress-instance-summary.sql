SELECT instance.job_code,
       count(*) AS total,
       count(*) FILTER (WHERE instance.instance_status = 'SUCCESS') AS success,
       count(*) FILTER (WHERE instance.instance_status <> 'SUCCESS') AS not_success,
       round(avg(extract(epoch FROM (instance.finished_at - instance.created_at)))
           FILTER (WHERE instance.finished_at IS NOT NULL)::numeric, 3) AS avg_seconds,
       round(percentile_cont(0.95) WITHIN GROUP (
           ORDER BY extract(epoch FROM (instance.finished_at - instance.created_at))
       ) FILTER (WHERE instance.finished_at IS NOT NULL)::numeric, 3) AS p95_seconds
FROM batch.job_instance instance
WHERE instance.tenant_id = :'tenant_id'
  AND instance.job_code IN (
      'import_customer_job', 'export_settlement_job',
      'lt_dispatch_local_job', 'lt_process_sql_job'
  )
  AND instance.params_snapshot::text LIKE '%' || :'run_id' || '%'
  AND instance.params_snapshot::text LIKE '%"stressUsers": ' || :'stress_users' || '%'
GROUP BY instance.job_code
ORDER BY instance.job_code;
