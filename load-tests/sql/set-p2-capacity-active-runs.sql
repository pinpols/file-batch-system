-- ADR-050：容量画像显式声明同一业务日允许的活跃根运行数。
-- 默认 1 与历史口径一致；调高后同日可并行多个 run，用于测量“业务日 × 上限”的容量。
UPDATE batch.workflow_definition workflow
   SET max_active_runs_per_biz_date = :'max_active_runs'::integer,
       updated_at = now()
  FROM batch.job_definition job
 WHERE workflow.tenant_id = job.tenant_id
   AND workflow.single_job_definition_id = job.id
   AND workflow.tenant_id = :'capacity_tenant_id'
   AND job.job_code = 'atomic_sql_demo';
