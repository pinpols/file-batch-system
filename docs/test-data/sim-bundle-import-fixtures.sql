-- =========================================================
-- sim-bundle-import-fixtures.sql:ADR-046 文件束导入 sim 阶段的平台 fixture
--
-- 派生 BUNDLE_IMPORT / BUNDLE_EXPORT / BUNDLE_DISPATCH 三个束作业(shard_strategy=DYNAMIC),
-- 分别复用已有 import / export / dispatch worker_group 与 queue,使束分区任务路由到对应 worker。
-- 幂等:先删后插,sim 阶段可重复跑。
--
-- 前置:sim-e2e-bootstrap.sql 已应用(提供 TA_IMPORT_CUSTOMER / TA_EXPORT_REPORT 等基础配置)。
-- =========================================================

DELETE FROM batch.job_definition
WHERE tenant_id = 'ta'
  AND job_code IN ('TA_BUNDLE_IMPORT', 'TA_BUNDLE_EXPORT', 'TA_BUNDLE_DISPATCH');

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
WHERE src.tenant_id = 'ta' AND src.job_code = 'TA_IMPORT_CUSTOMER';

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
WHERE src.tenant_id = 'ta' AND src.job_code = 'TA_EXPORT_REPORT';

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
LIMIT 1;

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
