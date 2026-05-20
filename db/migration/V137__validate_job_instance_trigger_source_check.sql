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
--   trigger_request_id IS NULL),需要先用以下脚本审计并人工补齐再重跑:
--     SELECT id, tenant_id, instance_no, trigger_type, created_at
--       FROM batch.job_instance
--      WHERE trigger_request_id IS NULL
--        AND trigger_type <> 'MANUAL';
-- =========================================================

ALTER TABLE batch.job_instance
    VALIDATE CONSTRAINT ck_job_instance_trigger_source;

ALTER TABLE archive.job_instance_archive
    VALIDATE CONSTRAINT ck_job_instance_archive_trigger_source;
