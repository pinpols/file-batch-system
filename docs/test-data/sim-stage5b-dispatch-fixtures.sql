-- ============================================================================
-- Stage 5b dispatch fixture:TB_DISPATCH_STAGE5_FAIL_ONCE job + tb_api_fail channel
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
    'Asia/Shanghai', 5, 'tb_dispatch_queue', 'DISPATCH', 'default_calendar', 'always_open',
    'API', false, 'NONE', 'NONE', 0, 3600, 'statementDispatchHandler', '{}', '{}', 1,
    true, 'Stage 5 dispatch HTTP 500 terminal failure', 'sim-e2e', 'sim-e2e', 'FULL', NULL,
    'INHERIT', NULL, NULL
) ON CONFLICT (tenant_id, job_code) DO NOTHING;

INSERT INTO batch.file_channel_config (
    tenant_id, channel_code, channel_name, channel_type, target_endpoint, auth_type,
    config_json, receipt_policy, timeout_seconds, enabled, is_deleted
) VALUES (
    'tb', 'tb_api_fail', 'TB API fail channel', 'API', 'http://localhost:11080/tb/fail', 'NONE',
    '{"authorization": "Bearer sim-token", "target_endpoint": "http://localhost:11080/tb/fail"}'::jsonb,
    'SYNC', 30, true, false
) ON CONFLICT (tenant_id, channel_code) DO NOTHING;
