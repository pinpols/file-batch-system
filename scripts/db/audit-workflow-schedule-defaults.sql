-- 用途：只读审查指定租户的当前 Workflow 调度缺省值与受管单作业配置差异，不修复数据。
-- 用法：psql -X -v ON_ERROR_STOP=1 -v tenant_id=目标租户 -f scripts/db/audit-workflow-schedule-defaults.sql
-- 需核对目标平台库并使用已有只读权限；结果不是自动回填或停调清单。
\set ON_ERROR_STOP on
BEGIN READ ONLY;
SET LOCAL statement_timeout = '10s';

WITH latest_definitions AS (
    SELECT wf.*
      FROM batch.workflow_definition wf
     WHERE wf.tenant_id = :'tenant_id'
       AND NOT EXISTS (
           SELECT 1
             FROM batch.workflow_definition newer
            WHERE newer.tenant_id = wf.tenant_id
              AND newer.workflow_code = wf.workflow_code
              AND newer.version > wf.version
       )
)
SELECT wf.tenant_id, wf.id, wf.workflow_code, wf.version,
       wf.enabled, wf.schedule_enabled, wf.schedule_type, wf.trigger_mode,
       wf.timezone AS workflow_timezone, job.timezone AS job_timezone,
       wf.single_job_definition_id, job.job_code,
       wf.created_at, wf.updated_at,
       (wf.enabled AND wf.schedule_enabled
        AND wf.schedule_type IN ('CRON', 'FIXED_RATE')
        AND wf.trigger_mode IN ('SCHEDULED', 'MIXED')
        AND NULLIF(BTRIM(wf.timezone), '') IS NULL) AS active_missing_timezone,
       (wf.single_job_definition_id IS NOT NULL
        AND wf.timezone IS DISTINCT FROM job.timezone) AS managed_timezone_differs
  FROM latest_definitions wf
  LEFT JOIN batch.job_definition job
    ON job.tenant_id = wf.tenant_id AND job.id = wf.single_job_definition_id
 WHERE (wf.enabled AND wf.schedule_enabled
        AND wf.schedule_type IN ('CRON', 'FIXED_RATE')
        AND wf.trigger_mode IN ('SCHEDULED', 'MIXED')
        AND NULLIF(BTRIM(wf.timezone), '') IS NULL)
    OR (wf.single_job_definition_id IS NOT NULL
        AND (wf.schedule_enabled OR wf.timezone IS DISTINCT FROM job.timezone))
 ORDER BY wf.workflow_code, wf.id;

ROLLBACK;
