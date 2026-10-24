BEGIN;


INSERT INTO batch.job_definition (
    tenant_id, job_code, job_name, job_type, biz_type, schedule_type, schedule_expr,
    timezone, priority, queue_code, worker_group, calendar_code, window_code,
    trigger_mode, dag_enabled, shard_strategy, retry_policy, retry_max_count,
    timeout_seconds, execution_handler, param_schema, default_params, version,
    enabled, description, created_by, updated_by, execution_mode,
    previous_day_dependency_scope, retry_policy_by_class
)
SELECT 'ta', m.job_code, m.job_name, 'PROCESS', 'PROCESS_STAGE4',
       'MANUAL', null, 'Asia/Shanghai', 5, 'process_queue', 'PROCESS',
       'default_calendar', 'always_open', 'API', false, 'NONE',
       'NONE', 0, 600, null, '{}'::jsonb, '{}'::jsonb, 1,
       true, m.description, 'sim-e2e', 'sim-e2e', 'FULL',
       'INHERIT', null
FROM (VALUES
    ('TA_PROCESS_STAGE4_JSONB', 'Stage4 JSONB process', 'Stage 4 JSONB staging success'),
    ('TA_PROCESS_STAGE4_DIRECT', 'Stage4 DIRECT process', 'Stage 4 DIRECT success'),
    ('TA_PROCESS_STAGE4_VALIDATE_FAIL', 'Stage4 validation failure process', 'Stage 4 validation failure'),
    ('TA_PROCESS_STAGE4_EMPTY_SUCCESS', 'Stage4 empty success process', 'Stage 4 empty result success')
) AS m(job_code, job_name, description)
ON CONFLICT (tenant_id, job_code) DO UPDATE
SET job_name = EXCLUDED.job_name,
    job_type = EXCLUDED.job_type,
    biz_type = EXCLUDED.biz_type,
    schedule_type = EXCLUDED.schedule_type,
    trigger_mode = EXCLUDED.trigger_mode,
    queue_code = EXCLUDED.queue_code,
    worker_group = EXCLUDED.worker_group,
    retry_policy = EXCLUDED.retry_policy,
    retry_max_count = EXCLUDED.retry_max_count,
    timeout_seconds = EXCLUDED.timeout_seconds,
    enabled = true,
    description = EXCLUDED.description,
    updated_by = EXCLUDED.updated_by,
    updated_at = CURRENT_TIMESTAMP,
    execution_mode = 'FULL';

-- PROCESS业务参数直接归属Job，步骤序列由系统目录固定。
WITH specs AS (
    SELECT *
    FROM (VALUES
      (
        'TA_PROCESS_STAGE4_JSONB',
        jsonb_build_object(
          'sqlTransformCompute', jsonb_build_object(
            'sourceSql',
            'SELECT tenant_id, scenario, account_id, biz_date, sum(amount) AS total_amount, count(*) AS event_count, max(event_id) AS high_water_mark FROM biz.process_stage4_source WHERE tenant_id = :tenantId AND biz_date = cast(:bizDate as date) AND scenario = ''JSONB'' GROUP BY tenant_id, scenario, account_id, biz_date LIMIT 10',
            'targetSchema', 'biz',
            'targetTable', 'process_stage4_target',
            'writeMode', 'UPSERT',
            'columns', jsonb_build_array(
              jsonb_build_object('source', 'tenant_id', 'target', 'tenant_id'),
              jsonb_build_object('source', 'scenario', 'target', 'scenario'),
              jsonb_build_object('source', 'account_id', 'target', 'account_id'),
              jsonb_build_object('source', 'biz_date', 'target', 'biz_date'),
              jsonb_build_object('source', 'total_amount', 'target', 'total_amount'),
              jsonb_build_object('source', 'event_count', 'target', 'event_count'),
              jsonb_build_object('source', 'high_water_mark', 'target', 'high_water_mark')),
            'conflictColumns', jsonb_build_array('tenant_id', 'scenario', 'account_id', 'biz_date'),
            'validations', jsonb_build_array(
              jsonb_build_object(
                'name', 'jsonb_two_accounts',
                'checkSql', 'select count(*) = 2 as pass, ''expected 2 staged accounts'' as message from batch.process_staging where tenant_id = :tenantId and target_schema = :targetSchema and target_table = :targetTable and batch_key = :batchKey')),
            'emptyResultPolicy', 'FAIL',
            'maxStagedRows', 10))
      ),
      (
        'TA_PROCESS_STAGE4_DIRECT',
        jsonb_build_object(
          'sqlTransformCompute', jsonb_build_object(
            'sourceSql',
            'SELECT tenant_id, scenario, account_id, biz_date, sum(amount) AS total_amount, count(*) AS event_count, max(event_id) AS high_water_mark FROM biz.process_stage4_source WHERE tenant_id = :tenantId AND biz_date = cast(:bizDate as date) AND scenario = ''DIRECT'' GROUP BY tenant_id, scenario, account_id, biz_date LIMIT 10',
            'targetSchema', 'biz',
            'targetTable', 'process_stage4_target',
            'writeMode', 'UPSERT',
            'stagingMode', 'DIRECT',
            'columns', jsonb_build_array(
              jsonb_build_object('source', 'tenant_id', 'target', 'tenant_id'),
              jsonb_build_object('source', 'scenario', 'target', 'scenario'),
              jsonb_build_object('source', 'account_id', 'target', 'account_id'),
              jsonb_build_object('source', 'biz_date', 'target', 'biz_date'),
              jsonb_build_object('source', 'total_amount', 'target', 'total_amount'),
              jsonb_build_object('source', 'event_count', 'target', 'event_count'),
              jsonb_build_object('source', 'high_water_mark', 'target', 'high_water_mark')),
            'conflictColumns', jsonb_build_array('tenant_id', 'scenario', 'account_id', 'biz_date'),
            'emptyResultPolicy', 'SUCCESS',
            'watermarkColumn', 'high_water_mark'))
      ),
      (
        'TA_PROCESS_STAGE4_VALIDATE_FAIL',
        jsonb_build_object(
          'sqlTransformCompute', jsonb_build_object(
            'sourceSql',
            'SELECT tenant_id, scenario, account_id, biz_date, sum(amount) AS total_amount, count(*) AS event_count, max(event_id) AS high_water_mark FROM biz.process_stage4_source WHERE tenant_id = :tenantId AND biz_date = cast(:bizDate as date) AND scenario = ''VALIDATE_FAIL'' GROUP BY tenant_id, scenario, account_id, biz_date LIMIT 10',
            'targetSchema', 'biz',
            'targetTable', 'process_stage4_target',
            'writeMode', 'UPSERT',
            'columns', jsonb_build_array(
              jsonb_build_object('source', 'tenant_id', 'target', 'tenant_id'),
              jsonb_build_object('source', 'scenario', 'target', 'scenario'),
              jsonb_build_object('source', 'account_id', 'target', 'account_id'),
              jsonb_build_object('source', 'biz_date', 'target', 'biz_date'),
              jsonb_build_object('source', 'total_amount', 'target', 'total_amount'),
              jsonb_build_object('source', 'event_count', 'target', 'event_count'),
              jsonb_build_object('source', 'high_water_mark', 'target', 'high_water_mark')),
            'conflictColumns', jsonb_build_array('tenant_id', 'scenario', 'account_id', 'biz_date'),
            'validations', jsonb_build_array(
              jsonb_build_object(
                'name', 'validate_fail_two_accounts',
                'checkSql', 'select count(*) = 2 as pass, ''expected validation failure: only 1 staged account'' as message from batch.process_staging where tenant_id = :tenantId and target_schema = :targetSchema and target_table = :targetTable and batch_key = :batchKey')),
            'emptyResultPolicy', 'FAIL',
            'maxStagedRows', 10))
      ),
      (
        'TA_PROCESS_STAGE4_EMPTY_SUCCESS',
        jsonb_build_object(
          'sqlTransformCompute', jsonb_build_object(
            'sourceSql',
            'SELECT tenant_id, scenario, account_id, biz_date, sum(amount) AS total_amount, count(*) AS event_count, max(event_id) AS high_water_mark FROM biz.process_stage4_source WHERE tenant_id = :tenantId AND biz_date = cast(:bizDate as date) AND scenario = ''EMPTY'' GROUP BY tenant_id, scenario, account_id, biz_date LIMIT 10',
            'targetSchema', 'biz',
            'targetTable', 'process_stage4_target',
            'writeMode', 'UPSERT',
            'columns', jsonb_build_array(
              jsonb_build_object('source', 'tenant_id', 'target', 'tenant_id'),
              jsonb_build_object('source', 'scenario', 'target', 'scenario'),
              jsonb_build_object('source', 'account_id', 'target', 'account_id'),
              jsonb_build_object('source', 'biz_date', 'target', 'biz_date'),
              jsonb_build_object('source', 'total_amount', 'target', 'total_amount'),
              jsonb_build_object('source', 'event_count', 'target', 'event_count'),
              jsonb_build_object('source', 'high_water_mark', 'target', 'high_water_mark')),
            'conflictColumns', jsonb_build_array('tenant_id', 'scenario', 'account_id', 'biz_date'),
            'emptyResultPolicy', 'SUCCESS',
            'maxStagedRows', 10))
      )
    ) AS s(job_code, step_params)
)
UPDATE batch.job_definition jd
SET default_params = jsonb_build_object(
        'processImplCode', 'sqlTransformCompute',
        'systemPipelineStepParams', jsonb_build_object('COMPUTE', specs.step_params)),
    updated_at = CURRENT_TIMESTAMP
FROM specs
WHERE jd.tenant_id = 'ta' AND jd.job_code = specs.job_code;



COMMIT;
