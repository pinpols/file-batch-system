-- =========================================================
-- V137: VALIDATE V136 引入的 NOT VALID 约束
--
-- 依据: docs/analysis/dba-schema-review-2026-05-20.md §3.4
--
-- 规范:
--   CLAUDE.md 红线 — NOT VALID 必须与 VALIDATE 同 PR / 同 sprint,
--   防止"逻辑生效但旧数据未校验"窗口期。
--
-- 失败时:
--   若 VALIDATE 失败说明历史有违例数据(SCHEDULED/API/EVENT/CATCH_UP 触发但
--   trigger_request_id IS NULL),需要先用 scripts/db/preflight-job-instance-trigger-source.sql
--   审计并人工补齐再重跑。核心查询:
--     SELECT id, tenant_id, instance_no, trigger_type, created_at
--       FROM batch.job_instance
--      WHERE trigger_request_id IS NULL
--        AND trigger_type <> 'MANUAL';
-- =========================================================

-- 历史测试/人工导入数据可能已经存在 "非 MANUAL 但无 trigger_request_id"。
-- 这些行不应阻断新版本启动；VALIDATE 前为它们补一条 synthetic trigger_request，
-- 保留原 trigger_type 语义，而不是把运行实例改成 MANUAL。
INSERT INTO batch.trigger_request (
    tenant_id,
    request_id,
    trigger_type,
    job_code,
    biz_date,
    dedup_key,
    request_payload_hash,
    request_status,
    related_job_instance_id,
    trace_id,
    created_at,
    updated_at
)
SELECT
    ji.tenant_id,
    'legacy-job-instance-' || ji.id,
    ji.trigger_type,
    ji.job_code,
    ji.biz_date,
    'legacy-job-instance-' || ji.id,
    NULL,
    'LAUNCHED',
    ji.id,
    ji.trace_id,
    ji.created_at,
    CURRENT_TIMESTAMP
FROM batch.job_instance ji
WHERE ji.trigger_request_id IS NULL
  AND ji.trigger_type <> 'MANUAL'
ON CONFLICT (tenant_id, request_id) DO NOTHING;

UPDATE batch.job_instance ji
SET trigger_request_id = tr.id,
    updated_at = CURRENT_TIMESTAMP
FROM batch.trigger_request tr
WHERE ji.trigger_request_id IS NULL
  AND ji.trigger_type <> 'MANUAL'
  AND tr.tenant_id = ji.tenant_id
  AND tr.request_id = 'legacy-job-instance-' || ji.id;

ALTER TABLE batch.job_instance
    VALIDATE CONSTRAINT ck_job_instance_trigger_source;

ALTER TABLE archive.job_instance_archive
    VALIDATE CONSTRAINT ck_job_instance_archive_trigger_source;
