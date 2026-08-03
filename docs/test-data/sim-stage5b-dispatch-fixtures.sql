-- ============================================================================
-- Stage 5b dispatch fixture：TB_DISPATCH_STAGE5_FAIL_ONCE job + tb_api_fail channel 用例
--
-- 这俩 fixture 在单机由 created_by='sim-e2e' 的外部 seed 建立,但 docs/test-data
-- 漏带定义文件,导致 14 的 preflight "missing TB_DISPATCH_STAGE5_FAIL_ONCE fixture"
-- 失败。此处补为 self-contained(值取自单机 main batch_platform 的权威行,去
-- id/时间戳让默认)。
--
-- channel target 指向 mockserver 的 /tb/fail:mockserver 对该 path 无 2xx 期望,
-- dispatch 收到非 2xx → retry_policy=NONE → job/partition/task FAILED、
-- file_dispatch_record COMPENSATED。
-- ============================================================================
INSERT INTO batch.job_definition (
    tenant_id, job_code, job_name, job_type, biz_type, schedule_type, schedule_expr,
    timezone, priority, queue_code, worker_group, calendar_code, window_code,
    trigger_mode, dag_enabled, shard_strategy, retry_policy, retry_max_count,
    timeout_seconds, execution_handler, param_schema, default_params, version,
    enabled, description, created_by, updated_by, execution_mode, watermark_field,
    previous_day_dependency_scope, job_group_code, retry_policy_by_class
) VALUES (
    'tb', 'TB_DISPATCH_STAGE5_FAIL_ONCE', 'TB dispatch stage5 fail once', 'DISPATCH', 'DISPATCH_STAGE5', 'MANUAL', NULL,
    'Asia/Shanghai', 5, 'tb_dispatch_queue', 'DISPATCH', 'default-calendar', 'always_open',
    'API', false, 'NONE', 'NONE', 0, 3600, 'statementDispatchHandler', '{}', '{}', 1,
    true, 'Stage 5 dispatch HTTP 500 terminal failure', 'sim-e2e', 'sim-e2e', 'FULL', NULL,
    'INHERIT', NULL, NULL
) ON CONFLICT (tenant_id, job_code) DO NOTHING;

INSERT INTO batch.pipeline_definition (
    tenant_id, job_code, pipeline_name, pipeline_type, biz_type,
    worker_group, version, enabled, description
) VALUES (
    'tb', 'TB_DISPATCH_STAGE5_FAIL_ONCE', 'TB dispatch stage5 fail once pipeline',
    'DISPATCH', 'DISPATCH_STAGE5', 'DISPATCH', 1, true,
    'Stage 5 dispatch failure and compensation pipeline'
) ON CONFLICT (tenant_id, job_code, version) DO UPDATE
SET enabled = EXCLUDED.enabled,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

WITH pd AS (
    SELECT id
    FROM batch.pipeline_definition
    WHERE tenant_id = 'tb'
      AND job_code = 'TB_DISPATCH_STAGE5_FAIL_ONCE'
      AND version = 1
), steps(stage_code, step_order, step_code, step_name, impl_code, step_params) AS (
    VALUES
      ('PREPARE',    1, 'STEP_PREPARE',    '分发准备', 'DISPATCH_PREPARE', '{}'::jsonb),
      ('DISPATCH',   2, 'STEP_DISPATCH',   '实际分发', 'DISPATCH_DISPATCH', '{}'::jsonb),
      ('ACK',        3, 'STEP_ACK',        '回执确认', 'DISPATCH_ACK', '{"onSuccessNextStageCode":"COMPLETE"}'::jsonb),
      ('RETRY',      4, 'STEP_RETRY',      '失败重试', 'DISPATCH_RETRY', '{"onFailureNextStageCode":"COMPENSATE"}'::jsonb),
      ('COMPENSATE', 5, 'STEP_COMPENSATE', '补偿处理', 'DISPATCH_COMPENSATE', '{"terminalOnSuccess":true}'::jsonb),
      ('COMPLETE',   6, 'STEP_COMPLETE',   '分发完成', 'DISPATCH_COMPLETE', '{"terminalOnSuccess":true}'::jsonb)
)
INSERT INTO batch.pipeline_step_definition (
    pipeline_definition_id, step_code, step_name, stage_code, step_order,
    impl_code, step_params, timeout_seconds, retry_policy, retry_max_count, enabled
)
SELECT pd.id, steps.step_code, steps.step_name, steps.stage_code, steps.step_order,
       steps.impl_code, steps.step_params, 300, 'NONE', 0, true
FROM pd CROSS JOIN steps
ON CONFLICT (pipeline_definition_id, step_code) DO UPDATE
SET stage_code = EXCLUDED.stage_code,
    step_order = EXCLUDED.step_order,
    impl_code = EXCLUDED.impl_code,
    step_params = EXCLUDED.step_params,
    enabled = EXCLUDED.enabled,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO batch.file_channel_config (
    tenant_id, channel_code, channel_name, channel_type, target_endpoint, auth_type,
    config_json, receipt_policy, timeout_seconds, enabled, is_deleted
) VALUES (
    'tb', 'tb_api_fail', 'TB API fail channel', 'API', 'http://localhost:11080/tb/fail', 'NONE',
    '{"authorization": "Bearer sim-token", "target_endpoint": "http://localhost:11080/tb/fail"}'::jsonb,
    'SYNC', 30, true, false
) ON CONFLICT (tenant_id, channel_code) DO NOTHING;
