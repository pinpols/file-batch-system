-- =========================================================
-- sim-bundle-import-fixtures.sql:ADR-046 文件束导入 sim 阶段的平台 fixture
--
-- 派生 BUNDLE_IMPORT / BUNDLE_EXPORT / BUNDLE_DISPATCH 三个束作业(shard_strategy=DYNAMIC),
-- 分别复用已有 import / export / dispatch worker_group 与 queue,使束分区任务路由到对应 worker;
-- 同时派生对应 pipeline_definition + 步骤(worker 缺 pipeline 会让分区任务直接 NOT_FOUND 死信)。
-- 幂等:ON CONFLICT (tenant_id, job_code) DO UPDATE,不删定义 —— 先删后插在有 job_instance
-- 引用 job_definition 时会撞 FK(重跑 sim 阶段必现),故与 sim-e2e-bootstrap.sql 同口径走 upsert。
--
-- 前置:sim-e2e-bootstrap.sql 已应用(提供 TA_IMPORT_CUSTOMER / TA_EXPORT_REPORT 等基础配置)。
-- =========================================================

INSERT INTO batch.job_definition (
    tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
    priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
    retry_policy, retry_max_count, timeout_seconds, enabled, version
)
SELECT
    'ta', 'TA_BUNDLE_IMPORT', '文件束导入(sim)', 'BUNDLE_IMPORT', src.biz_type, 'MANUAL', src.timezone,
    src.priority, src.queue_code, src.worker_group, 'API', false, 'DYNAMIC',
    'NONE', 0, COALESCE(NULLIF(src.timeout_seconds, 0), 600), true, 1
FROM batch.job_definition src
WHERE src.tenant_id = 'ta' AND src.job_code = 'TA_IMPORT_CUSTOMER'
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
    enabled = true,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO batch.job_definition (
    tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
    priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
    retry_policy, retry_max_count, timeout_seconds, enabled, version
)
SELECT
    'ta', 'TA_BUNDLE_EXPORT', '文件束导出(sim)', 'BUNDLE_EXPORT', src.biz_type, 'MANUAL', src.timezone,
    src.priority, src.queue_code, src.worker_group, 'API', false, 'DYNAMIC',
    'NONE', 0, COALESCE(NULLIF(src.timeout_seconds, 0), 600), true, 1
FROM batch.job_definition src
WHERE src.tenant_id = 'ta' AND src.job_code = 'TA_EXPORT_REPORT'
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
    enabled = true,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO batch.job_definition (
    tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
    priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
    retry_policy, retry_max_count, timeout_seconds, enabled, version
)
SELECT
    'ta', 'TA_BUNDLE_DISPATCH', '文件束分发(sim)', 'BUNDLE_DISPATCH', src.biz_type, 'MANUAL', src.timezone,
    src.priority, src.queue_code, src.worker_group, 'API', false, 'DYNAMIC',
    'NONE', 0, COALESCE(NULLIF(src.timeout_seconds, 0), 600), true, 1
FROM batch.job_definition src
WHERE src.tenant_id = 'tb' AND src.job_code = 'TB_DISPATCH_SETTLE'
LIMIT 1
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
    enabled = true,
    updated_at = CURRENT_TIMESTAMP;

-- 派生 pipeline_definition:worker 执行分区任务时按 job_code 解析 pipeline,缺失会直接落
-- error.pipeline.definition_not_found(AS 级失败→死信)。bootstrap §3b 只覆盖 EXPORT/DISPATCH,
-- 三个 BUNDLE_* 作业不在其中,故此处按源作业派生(口径与 bootstrap 生成的最小 pipeline 一致)。
INSERT INTO batch.pipeline_definition (
    tenant_id, job_code, pipeline_name, pipeline_type, biz_type,
    worker_group, version, enabled, description
)
SELECT m.dst_tenant, m.dst_job_code, src.pipeline_name || '(bundle sim)', src.pipeline_type,
       src.biz_type, src.worker_group, 1, true, 'sim bundle fixture derived pipeline'
FROM (VALUES
    ('ta', 'TA_BUNDLE_IMPORT',   'ta', 'TA_IMPORT_CUSTOMER'),
    ('ta', 'TA_BUNDLE_EXPORT',   'ta', 'TA_EXPORT_REPORT'),
    ('ta', 'TA_BUNDLE_DISPATCH', 'tb', 'TB_DISPATCH_SETTLE')
) AS m(dst_tenant, dst_job_code, src_tenant, src_job_code)
JOIN batch.pipeline_definition src
  ON src.tenant_id = m.src_tenant AND src.job_code = m.src_job_code AND src.version = 1
ON CONFLICT (tenant_id, job_code, version) DO UPDATE
SET pipeline_name = EXCLUDED.pipeline_name,
    pipeline_type = EXCLUDED.pipeline_type,
    biz_type = EXCLUDED.biz_type,
    worker_group = EXCLUDED.worker_group,
    enabled = true;

-- 派生 pipeline 步骤:IMPORT 束要跑 RECEIVE/PREPROCESS/PARSE/VALIDATE/LOAD(否则作业空转到不了入库),
-- 直接复制源作业的步骤集合,保证与已跑通的源作业同口径。
INSERT INTO batch.pipeline_step_definition (
    pipeline_definition_id, step_code, step_name, stage_code, step_order,
    impl_code, step_params, timeout_seconds, retry_policy, retry_max_count, enabled
)
SELECT dst_pd.id, s.step_code, s.step_name, s.stage_code, s.step_order,
       s.impl_code, s.step_params, s.timeout_seconds, s.retry_policy, s.retry_max_count, s.enabled
FROM (VALUES
    ('ta', 'TA_BUNDLE_IMPORT',   'ta', 'TA_IMPORT_CUSTOMER'),
    ('ta', 'TA_BUNDLE_EXPORT',   'ta', 'TA_EXPORT_REPORT'),
    ('ta', 'TA_BUNDLE_DISPATCH', 'tb', 'TB_DISPATCH_SETTLE')
) AS m(dst_tenant, dst_job_code, src_tenant, src_job_code)
JOIN batch.pipeline_definition dst_pd
  ON dst_pd.tenant_id = m.dst_tenant AND dst_pd.job_code = m.dst_job_code AND dst_pd.version = 1
JOIN batch.pipeline_definition src_pd
  ON src_pd.tenant_id = m.src_tenant AND src_pd.job_code = m.src_job_code
JOIN batch.pipeline_step_definition s ON s.pipeline_definition_id = src_pd.id
ON CONFLICT (pipeline_definition_id, step_code) DO UPDATE
SET step_name = EXCLUDED.step_name,
    stage_code = EXCLUDED.stage_code,
    step_order = EXCLUDED.step_order,
    impl_code = EXCLUDED.impl_code,
    step_params = EXCLUDED.step_params,
    timeout_seconds = EXCLUDED.timeout_seconds,
    retry_policy = EXCLUDED.retry_policy,
    retry_max_count = EXCLUDED.retry_max_count,
    enabled = EXCLUDED.enabled;

INSERT INTO batch.file_channel_config (
    tenant_id, channel_code, channel_name, channel_type, target_endpoint, auth_type,
    config_json, receipt_policy, timeout_seconds, enabled
)
VALUES (
    'ta', 'ta_bundle_local', 'TA bundle local dispatch(sim)', 'LOCAL', null, 'NONE',
    jsonb_build_object(
        'target_endpoint', '/tmp/batch-sim-bundle-dispatch',
        'receipt_policy', 'NONE',
        'channel_type', 'LOCAL',
        'channel_code', 'ta_bundle_local'
    ),
    'NONE', 10, true
)
ON CONFLICT (tenant_id, channel_code) DO UPDATE
SET channel_name = EXCLUDED.channel_name,
    channel_type = EXCLUDED.channel_type,
    config_json = EXCLUDED.config_json,
    receipt_policy = EXCLUDED.receipt_policy,
    timeout_seconds = EXCLUDED.timeout_seconds,
    enabled = true,
    updated_at = CURRENT_TIMESTAMP;
