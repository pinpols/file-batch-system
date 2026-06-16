-- Stage 5c 分发通道矩阵的 platform 库 fixtures。
-- 需要的 psql 变量：sftp_host, sftp_port

INSERT INTO batch.job_definition (
    tenant_id, job_code, job_name, job_type, biz_type,
    schedule_type, schedule_expr, timezone, priority, queue_code, worker_group,
    trigger_mode, dag_enabled, shard_strategy, retry_policy, retry_max_count,
    timeout_seconds, execution_handler, default_params, version, enabled,
    description, created_by, updated_by, execution_mode
)
VALUES (
    'tb', 'TB_DISPATCH_STAGE5C_CHANNELS', 'TB Dispatch Stage5c Channel Matrix',
    'DISPATCH', 'DISPATCH_STAGE5C',
    'MANUAL', null, 'Asia/Shanghai', 5, 'default', 'DISPATCH',
    'API', false, 'NONE', 'NONE', 0,
    300, 'statementDispatchHandler', '{}'::jsonb, 1, true,
    'Stage 5c dispatch LOCAL/NAS/SFTP channel matrix', 'sim', 'sim', 'FULL'
)
ON CONFLICT (tenant_id, job_code) DO UPDATE
SET job_name = EXCLUDED.job_name,
    job_type = EXCLUDED.job_type,
    biz_type = EXCLUDED.biz_type,
    schedule_type = EXCLUDED.schedule_type,
    timezone = EXCLUDED.timezone,
    priority = EXCLUDED.priority,
    queue_code = EXCLUDED.queue_code,
    worker_group = EXCLUDED.worker_group,
    trigger_mode = EXCLUDED.trigger_mode,
    dag_enabled = EXCLUDED.dag_enabled,
    shard_strategy = EXCLUDED.shard_strategy,
    retry_policy = EXCLUDED.retry_policy,
    retry_max_count = EXCLUDED.retry_max_count,
    timeout_seconds = EXCLUDED.timeout_seconds,
    execution_handler = EXCLUDED.execution_handler,
    default_params = EXCLUDED.default_params,
    enabled = EXCLUDED.enabled,
    description = EXCLUDED.description,
    updated_by = EXCLUDED.updated_by,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO batch.pipeline_definition (
    tenant_id, job_code, pipeline_name, pipeline_type, biz_type,
    worker_group, version, enabled, description
)
VALUES (
    'tb', 'TB_DISPATCH_STAGE5C_CHANNELS', 'TB dispatch stage5c channel matrix pipeline',
    'DISPATCH', 'DISPATCH_STAGE5C', 'DISPATCH', 1, true,
    'PREPARE→DISPATCH→ACK→RETRY→COMPENSATE→COMPLETE for channel matrix'
)
ON CONFLICT (tenant_id, job_code, version) DO UPDATE
SET pipeline_name = EXCLUDED.pipeline_name,
    pipeline_type = EXCLUDED.pipeline_type,
    biz_type = EXCLUDED.biz_type,
    worker_group = EXCLUDED.worker_group,
    enabled = EXCLUDED.enabled,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

WITH pd AS (
  SELECT id
  FROM batch.pipeline_definition
  WHERE tenant_id = 'tb'
    AND job_code = 'TB_DISPATCH_STAGE5C_CHANNELS'
    AND version = 1
),
steps(stage_code, step_order, step_name, step_params) AS (
  VALUES
    ('PREPARE', 1, '分发前准备', '{}'::jsonb),
    ('DISPATCH', 2, '实际下发', '{}'::jsonb),
    ('ACK', 3, '回执确认', '{"onSuccessNextStageCode":"COMPLETE"}'::jsonb),
    ('RETRY', 4, '失败重试', '{"onFailureNextStageCode":"COMPENSATE"}'::jsonb),
    ('COMPENSATE', 5, '补偿冲正', '{"terminalOnSuccess":true}'::jsonb),
    ('COMPLETE', 6, '完结', '{"terminalOnSuccess":true}'::jsonb)
)
INSERT INTO batch.pipeline_step_definition (
    pipeline_definition_id, step_code, step_name, stage_code, step_order, impl_code,
    step_params, timeout_seconds, retry_policy, retry_max_count, enabled
)
SELECT pd.id,
       'DISPATCH_' || steps.stage_code,
       steps.step_name,
       steps.stage_code,
       steps.step_order,
       'DISPATCH_' || steps.stage_code,
       steps.step_params,
       300,
       'NONE',
       0,
       true
FROM pd CROSS JOIN steps
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
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO batch.file_channel_config (
    tenant_id, channel_code, channel_name, channel_type, target_endpoint,
    auth_type, config_json, receipt_policy, timeout_seconds, enabled, is_deleted
)
VALUES
  (
    'tb', 'tb_stage5c_local', 'Stage5c local outbox', 'LOCAL',
    '/tmp/batch/stage5c-local',
    'NONE',
    jsonb_build_object(
      'channel_code', 'tb_stage5c_local',
      'channel_type', 'LOCAL',
      'target_endpoint', '/tmp/batch/stage5c-local',
      'receipt_policy', 'SYNC'
    ),
    'SYNC', 30, true, false
  ),
  (
    'tb', 'tb_stage5c_nas', 'Stage5c NAS stub outbox', 'NAS',
    '/tmp/batch/stage5c-nas',
    'NONE',
    jsonb_build_object(
      'channel_code', 'tb_stage5c_nas',
      'channel_type', 'NAS',
      'target_endpoint', '/tmp/batch/stage5c-nas',
      'receipt_policy', 'SYNC'
    ),
    'SYNC', 30, true, false
  ),
  (
    'tb', 'tb_stage5c_sftp', 'Stage5c SFTP manifest upload', 'SFTP',
    :'sftp_host' || ':' || :'sftp_port',
    'PASSWORD',
    jsonb_build_object(
      'channel_code', 'tb_stage5c_sftp',
      'channel_type', 'SFTP',
      'sftp_host', :'sftp_host',
      'sftp_port', :'sftp_port'::int,
      'sftp_user', 'tb',
      'sftp_password', 'tb_pass_123',
      'sftp_remote_directory', '/outbound',
      'sftp_remote_file_name', 'stage5c-dispatch.json',
      'sftp_strict_host_key_checking', 'no',
      'receipt_policy', 'SYNC',
      'dispatch_manifest_enabled', true,
      'dispatch_manifest_suffix', '.chk'
    ),
    'SYNC', 30, true, false
  )
ON CONFLICT (tenant_id, channel_code) DO UPDATE
SET channel_name = EXCLUDED.channel_name,
    channel_type = EXCLUDED.channel_type,
    target_endpoint = EXCLUDED.target_endpoint,
    auth_type = EXCLUDED.auth_type,
    config_json = EXCLUDED.config_json,
    receipt_policy = EXCLUDED.receipt_policy,
    timeout_seconds = EXCLUDED.timeout_seconds,
    enabled = EXCLUDED.enabled,
    is_deleted = EXCLUDED.is_deleted,
    updated_at = CURRENT_TIMESTAMP;
