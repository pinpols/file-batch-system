-- 保留父进程导出的所选环境，同时避免
-- 为本地模拟夹具中的 Job 创建最小化的受管 Workflow。
BEGIN;

INSERT INTO batch.workflow_definition (
    tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
    description, created_by, updated_by, schedule_type, trigger_mode,
    calendar_code, schedule_enabled, single_job_definition_id, created_at, updated_at
)
SELECT job.tenant_id, 'JOB-WF-' || job.id, job.job_name || ' Workflow', 'DAG', 1, true,
       'Local simulation managed single-job Workflow fixture ' || :'fixture_tag',
       left(:'fixture_tag', 64), left(:'fixture_tag', 64),
       'MANUAL', 'API', 'default_calendar', false, job.id, now(), now()
FROM batch.job_definition job
WHERE job.tenant_id = :'tenant_id'
  AND job.job_code = :'job_code'
  AND NOT EXISTS (
      SELECT 1
      FROM batch.workflow_definition existing
      WHERE existing.tenant_id = job.tenant_id
        AND existing.single_job_definition_id = job.id
  )
ON CONFLICT DO NOTHING;

-- 运行时只能通过 Workflow 启动；此脚本用于准备夹具，不提供兼容逻辑。
-- 现有夹具创建的受管 Workflow 可能早于日历继承功能。保持
-- Workflow 的批次日标识与其 Job 一致；当 Job 未显式配置业务日历时，使用租户默认日历。
UPDATE batch.workflow_definition workflow
   SET calendar_code = COALESCE(NULLIF(btrim(job.calendar_code), ''), 'default_calendar'),
       updated_at = now()
  FROM batch.job_definition job
 WHERE workflow.tenant_id = job.tenant_id
   AND workflow.single_job_definition_id = job.id
   AND workflow.workflow_code = 'JOB-WF-' || job.id
   AND workflow.tenant_id = :'tenant_id'
   AND job.job_code = :'job_code'
   AND NULLIF(btrim(workflow.calendar_code), '') IS NULL;

INSERT INTO batch.workflow_node (
    tenant_id, workflow_definition_id, node_code, node_name, node_type,
    related_job_code, node_order, retry_policy, retry_max_count,
    timeout_seconds, enabled, created_at, updated_at
)
SELECT workflow.tenant_id, workflow.id, node.node_code, node.node_name, node.node_type,
       CASE WHEN node.node_type = 'JOB' THEN job.job_code END,
       node.node_order, 'NONE', 0, 0, true, now(), now()
FROM batch.job_definition job
JOIN batch.workflow_definition workflow
  ON workflow.tenant_id = job.tenant_id
 AND workflow.single_job_definition_id = job.id
 AND workflow.workflow_code = 'JOB-WF-' || job.id
CROSS JOIN (VALUES
    ('START', 'Start', 'START', 0),
    ('JOB', 'Execute Job', 'JOB', 1),
    ('END', 'End', 'END', 2)
) AS node(node_code, node_name, node_type, node_order)
WHERE job.tenant_id = :'tenant_id'
  AND job.job_code = :'job_code'
ON CONFLICT (tenant_id, workflow_definition_id, node_code) DO NOTHING;

INSERT INTO batch.workflow_edge (
    tenant_id, workflow_definition_id, from_node_code, to_node_code,
    edge_type, enabled, created_at, updated_at
)
SELECT workflow.tenant_id, workflow.id, edge.from_node_code, edge.to_node_code,
       'SUCCESS', true, now(), now()
FROM batch.job_definition job
JOIN batch.workflow_definition workflow
  ON workflow.tenant_id = job.tenant_id
 AND workflow.single_job_definition_id = job.id
 AND workflow.workflow_code = 'JOB-WF-' || job.id
CROSS JOIN (VALUES ('START', 'JOB'), ('JOB', 'END')) AS edge(from_node_code, to_node_code)
WHERE job.tenant_id = :'tenant_id'
  AND job.job_code = :'job_code'
ON CONFLICT (tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type)
DO NOTHING;

COMMIT;
