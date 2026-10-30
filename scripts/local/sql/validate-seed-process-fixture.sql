WITH job_upsert AS (
  INSERT INTO batch.job_definition (
      tenant_id, job_code, job_name, job_type, biz_type,
      schedule_type, timezone, trigger_mode, queue_code, worker_group, window_code,
      default_params, priority, enabled, created_at, updated_at
  ) VALUES (
      'default-tenant', :'probe_process_job', 'seedval process probe', 'PROCESS', 'TEST',
      'MANUAL', 'Asia/Shanghai', 'API', 'process_queue', 'PROCESS', 'always_open',
      jsonb_build_object(
        'processImplCode', 'sqlTransformCompute',
        'systemPipelineStepParams', jsonb_build_object(
          'COMPUTE', jsonb_build_object(
            'sqlTransformCompute', jsonb_build_object(
              'sourceSql', 'select ' ||
                quote_literal('default-tenant') || '::text as tenant_id, ' ||
                quote_literal('SEEDVAL_' || upper(replace(:'probe_tag', '-', '_'))) || '::text as customer_no, ' ||
                quote_literal('Seed Validation Process Probe') || '::text as customer_name, ' ||
                quote_literal('PERSONAL') || '::text as customer_type, ' ||
                quote_literal('ACTIVE') || '::text as status',
              'targetSchema', 'biz',
              'targetTable', 'customer_account',
              'writeMode', 'UPSERT',
              'columns', jsonb_build_array(
                jsonb_build_object('source', 'tenant_id', 'target', 'tenant_id'),
                jsonb_build_object('source', 'customer_no', 'target', 'customer_no'),
                jsonb_build_object('source', 'customer_name', 'target', 'customer_name'),
                jsonb_build_object('source', 'customer_type', 'target', 'customer_type'),
                jsonb_build_object('source', 'status', 'target', 'status')
              ),
              'conflictColumns', jsonb_build_array('tenant_id', 'customer_no'),
              'validations', jsonb_build_array(
                jsonb_build_object(
                  'name', 'staged_one_row',
                  'checkSql', 'select count(*) = 1 as pass, ''expected one staged row'' as message from batch.process_staging where batch_key = :batchKey'
                )
              ),
              'emptyResultPolicy', 'FAIL',
              'maxStagedRows', 10
            )
          )
        )
      ),
      5, true, now(), now()
  )
  ON CONFLICT (tenant_id, job_code) DO UPDATE
  SET job_name = EXCLUDED.job_name,
      job_type = EXCLUDED.job_type,
      default_params = EXCLUDED.default_params,
      enabled = true,
      updated_at = CURRENT_TIMESTAMP
  RETURNING tenant_id, id, job_code
), workflow_upsert AS (
  INSERT INTO batch.workflow_definition (
      tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
      description, created_by, updated_by, schedule_type, trigger_mode,
      calendar_code, schedule_enabled, single_job_definition_id, created_at, updated_at
  )
  SELECT tenant_id, 'JOB-WF-' || id, job_code || ' 单作业工作流', 'DAG', 1, true,
         'seedval process probe', :'probe_tag', :'probe_tag', 'MANUAL', 'API',
         'always_open', false, id, now(), now()
  FROM job_upsert
  ON CONFLICT (tenant_id, workflow_code, version) DO UPDATE
  SET enabled = true,
      single_job_definition_id = EXCLUDED.single_job_definition_id,
      schedule_type = EXCLUDED.schedule_type,
      trigger_mode = EXCLUDED.trigger_mode,
      calendar_code = EXCLUDED.calendar_code,
      schedule_enabled = false,
      updated_at = CURRENT_TIMESTAMP
  RETURNING tenant_id, id AS workflow_id, single_job_definition_id
), node_upsert AS (
  INSERT INTO batch.workflow_node (
      tenant_id, workflow_definition_id, node_code, node_name, node_type,
      related_job_code, node_order, retry_policy, retry_max_count,
      timeout_seconds, enabled, created_at, updated_at
  )
  SELECT workflow.tenant_id, workflow.workflow_id, node.node_code, node.node_name,
         node.node_type, CASE WHEN node.node_type = 'JOB' THEN job.job_code END,
         node.node_order, 'NONE', 0, 0, true, now(), now()
  FROM workflow_upsert workflow
  JOIN job_upsert job ON job.id = workflow.single_job_definition_id
  CROSS JOIN (VALUES
      ('START', '开始', 'START', 0),
      ('JOB', 'PROCESS probe', 'JOB', 1),
      ('END', '结束', 'END', 2)
  ) AS node(node_code, node_name, node_type, node_order)
  ON CONFLICT (tenant_id, workflow_definition_id, node_code) DO UPDATE
  SET node_name = EXCLUDED.node_name,
      node_type = EXCLUDED.node_type,
      related_job_code = EXCLUDED.related_job_code,
      node_order = EXCLUDED.node_order,
      enabled = true,
      updated_at = CURRENT_TIMESTAMP
  RETURNING tenant_id, workflow_definition_id
)
INSERT INTO batch.workflow_edge (
    tenant_id, workflow_definition_id, from_node_code, to_node_code,
    edge_type, enabled, created_at, updated_at
)
SELECT DISTINCT node.tenant_id, node.workflow_definition_id, edge.from_node_code,
       edge.to_node_code, 'SUCCESS', true, now(), now()
FROM node_upsert node
CROSS JOIN (VALUES ('START', 'JOB'), ('JOB', 'END')) AS edge(from_node_code, to_node_code)
ON CONFLICT (tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type)
DO UPDATE SET enabled = true, updated_at = CURRENT_TIMESTAMP;
