WITH job_upsert AS (
  INSERT INTO batch.job_definition (
      tenant_id, job_code, job_name, job_type, biz_type,
      schedule_type, timezone, trigger_mode, queue_code, worker_group, window_code,
      priority, enabled, created_at, updated_at
  ) VALUES (
      'default-tenant', :'probe_process_job', 'seedval process probe', 'PROCESS', 'TEST',
      'MANUAL', 'Asia/Shanghai', 'SCHEDULED', 'process_queue', 'PROCESS', 'always_open',
      5, true, now(), now()
  )
  ON CONFLICT (tenant_id, job_code) DO UPDATE SET enabled = true
  RETURNING job_code
),
pipeline_upsert AS (
  INSERT INTO batch.pipeline_definition (
      tenant_id, job_code, pipeline_name, pipeline_type, biz_type, worker_group,
      version, enabled, description, created_at, updated_at
  ) VALUES (
      'default-tenant', :'probe_process_job', 'seedval process probe pipeline',
      'PROCESS', 'TEST', 'PROCESS', 1, true, 'seed validation process probe', now(), now()
  )
  ON CONFLICT (tenant_id, job_code, version) DO UPDATE
  SET enabled = true,
      -- Citus:DO UPDATE SET 函数须 IMMUTABLE;CURRENT_TIMESTAMP 改 EXCLUDED 引用(双栈语义等价)
      updated_at = EXCLUDED.updated_at
  RETURNING id
),
step_specs AS (
  SELECT *
  FROM (VALUES
      ('PROCESS_PREPARE', 'Prepare', 'PREPARE', 1, 'PROCESS_PREPARE', '{}'::jsonb, 300, 'NONE', 0),
      (
        'PROCESS_COMPUTE',
        'Compute',
        'COMPUTE',
        2,
        'sqlTransformCompute',
        jsonb_build_object(
          'sqlTransformCompute', jsonb_build_object(
            'sourceSql',
            'select ' ||
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
        ),
        600,
        'FIXED',
        1
      ),
      ('PROCESS_VALIDATE', 'Validate', 'VALIDATE', 3, 'PROCESS_VALIDATE', '{}'::jsonb, 300, 'NONE', 0),
      ('PROCESS_COMMIT', 'Commit', 'COMMIT', 4, 'PROCESS_COMMIT', '{}'::jsonb, 300, 'NONE', 0),
      ('PROCESS_FEEDBACK', 'Feedback', 'FEEDBACK', 5, 'PROCESS_FEEDBACK', '{}'::jsonb, 300, 'NONE', 0)
  ) AS s(step_code, step_name, stage_code, step_order, impl_code, step_params, timeout_seconds, retry_policy, retry_max_count)
)
INSERT INTO batch.pipeline_step_definition (
    pipeline_definition_id, step_code, step_name, stage_code, step_order,
    impl_code, step_params, timeout_seconds, retry_policy, retry_max_count,
    enabled, created_at, updated_at
)
SELECT p.id, s.step_code, s.step_name, s.stage_code, s.step_order,
       s.impl_code, s.step_params, s.timeout_seconds, s.retry_policy, s.retry_max_count,
       true, now(), now()
FROM pipeline_upsert p
CROSS JOIN step_specs s
ON CONFLICT (pipeline_definition_id, step_code) DO UPDATE
SET step_name = EXCLUDED.step_name,
    stage_code = EXCLUDED.stage_code,
    step_order = EXCLUDED.step_order,
    impl_code = EXCLUDED.impl_code,
    step_params = EXCLUDED.step_params,
    timeout_seconds = EXCLUDED.timeout_seconds,
    retry_policy = EXCLUDED.retry_policy,
    retry_max_count = EXCLUDED.retry_max_count,
    enabled = EXCLUDED.enabled,
    -- Citus:distributed 表 DO UPDATE SET 函数须 IMMUTABLE;CURRENT_TIMESTAMP(STABLE)改 EXCLUDED 引用
    -- (值=INSERT SELECT 的 now(),同语句同值,双栈语义等价)。
    updated_at = EXCLUDED.updated_at;
