SELECT DISTINCT job_code, biz_date
FROM :"replay_schema".forensic_job_instances
WHERE tenant_id = :'tenant_id'
  AND job_code IS NOT NULL
  AND biz_date IS NOT NULL
ORDER BY 1, 2;
