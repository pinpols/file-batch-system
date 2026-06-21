-- =========================================================
-- sim-bundle-import-fixtures.sql:ADR-046 文件束导入 sim 阶段的平台 fixture
--
-- 派生一个 BUNDLE_IMPORT 作业(TA_BUNDLE_IMPORT,shard_strategy=DYNAMIC),
-- 复用已有 TA_IMPORT_CUSTOMER 的 worker_group / queue,使束分区任务路由到同一批
-- import worker;每个束文件用 TA_IMPORT_CUSTOMER_TPL 模板(→ biz.customer_account)。
-- 幂等:先删后插,sim 阶段可重复跑。
--
-- 前置:sim-e2e-bootstrap.sql 已应用(提供 TA_IMPORT_CUSTOMER 作业 + TA_IMPORT_CUSTOMER_TPL 模板)。
-- =========================================================

DELETE FROM batch.job_definition WHERE tenant_id = 'ta' AND job_code = 'TA_BUNDLE_IMPORT';

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
