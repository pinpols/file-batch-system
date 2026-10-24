-- Stage 4c Process 平台 fixture:分片 process + 长时运行可取消 job。

BEGIN;


INSERT INTO batch.job_definition (
    tenant_id, job_code, job_name, job_type, biz_type, schedule_type, schedule_expr,
    timezone, priority, queue_code, worker_group, calendar_code, window_code,
    trigger_mode, dag_enabled, shard_strategy, retry_policy, retry_max_count,
    timeout_seconds, execution_handler, param_schema, default_params, version,
    enabled, description, created_by, updated_by, execution_mode,
    previous_day_dependency_scope, retry_policy_by_class
)
SELECT 'ta', m.job_code, m.job_name, 'PROCESS', 'PROCESS_STAGE4C',
       'MANUAL', null, 'Asia/Shanghai', 5, 'process_queue', 'PROCESS',
       'default_calendar', 'always_open', 'API', false, m.shard_strategy,
       'NONE', 0, m.timeout_seconds, null, '{}'::jsonb, '{}'::jsonb, 1,
       true, m.description, 'sim-e2e', 'sim-e2e', 'FULL',
       'INHERIT', null
FROM (VALUES
    ('TA_PROCESS_STAGE4_SHARDED', 'Stage4c sharded process', 'STATIC', 600,
     'Stage 4c sharded process using partitionNo/partitionCount runtime SQL params'),
    ('TA_PROCESS_STAGE4_CANCEL', 'Stage4c cancellable process', 'NONE', 600,
     'Stage 4c long-running process cancel scenario')
) AS m(job_code, job_name, shard_strategy, timeout_seconds, description)
ON CONFLICT (tenant_id, job_code) DO UPDATE
SET job_name = EXCLUDED.job_name,
    job_type = EXCLUDED.job_type,
    biz_type = EXCLUDED.biz_type,
    schedule_type = EXCLUDED.schedule_type,
    trigger_mode = EXCLUDED.trigger_mode,
    queue_code = EXCLUDED.queue_code,
    worker_group = EXCLUDED.worker_group,
    shard_strategy = EXCLUDED.shard_strategy,
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
        'TA_PROCESS_STAGE4_SHARDED',
        jsonb_build_object(
          'sqlTransformCompute', jsonb_build_object(
            'sourceSql',
            'SELECT tenant_id, scenario, account_id, biz_date, sum(amount) AS total_amount, count(*) AS event_count, max(event_id) AS high_water_mark FROM biz.process_stage4_source WHERE tenant_id = :tenantId AND biz_date = cast(:bizDate as date) AND scenario = ''SHARDED'' AND mod(event_id - 1, cast(:partitionCount as int)) = cast(:partitionNo as int) - 1 GROUP BY tenant_id, scenario, account_id, biz_date LIMIT 100',
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
                'name', 'sharded_four_rows',
                'checkSql', 'select count(*) = 4 as pass, ''expected exactly 4 rows per partition'' as message from batch.process_staging where tenant_id = :tenantId and target_schema = :targetSchema and target_table = :targetTable and batch_key = :batchKey')),
            'emptyResultPolicy', 'FAIL',
            'maxStagedRows', 100))
      ),
      (
        'TA_PROCESS_STAGE4_CANCEL',
        jsonb_build_object(
          'sqlTransformCompute', jsonb_build_object(
            'sourceSql',
            'SELECT src.tenant_id, src.scenario, src.account_id, src.biz_date, src.amount AS total_amount, 1::bigint AS event_count, src.event_id AS high_water_mark FROM biz.process_stage4_source src CROSS JOIN biz.process_stage4_source spin1 CROSS JOIN biz.process_stage4_source spin2 CROSS JOIN biz.process_stage4_source spin3 CROSS JOIN biz.process_stage4_source spin4 CROSS JOIN biz.process_stage4_source spin5 CROSS JOIN biz.process_stage4_source spin6 WHERE src.tenant_id = :tenantId AND src.biz_date = cast(:bizDate as date) AND src.scenario = ''CANCEL'' ORDER BY spin1.event_id + spin2.event_id + spin3.event_id + spin4.event_id + spin5.event_id + spin6.event_id DESC LIMIT 1',
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
            'emptyResultPolicy', 'FAIL',
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
