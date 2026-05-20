-- =========================================================
-- preflight-job-instance-trigger-source.sql
-- V136/V137 上线前预检:确认 job_instance 触发来源约束不会在 VALIDATE 阶段失败。
--
-- 用法:
--   PGPASSWORD=... psql -h localhost -p 15432 -U batch_user -d batch_platform \
--     -v ON_ERROR_STOP=1 -f scripts/db/preflight-job-instance-trigger-source.sql
--
-- 结果:
--   - 返回 0 行并正常退出:可以执行 V136/V137
--   - 返回任意行后以异常退出:先人工补齐 trigger_request_id 或确认是否应改为 MANUAL
-- =========================================================

\set ON_ERROR_STOP on

\echo '=== batch.job_instance trigger source violations ==='
SELECT id, tenant_id, instance_no, trigger_type, trigger_request_id, created_at
FROM batch.job_instance
WHERE trigger_request_id IS NULL
  AND trigger_type <> 'MANUAL'
ORDER BY created_at, id;

\echo '=== archive.job_instance_archive trigger source violations ==='
SELECT id, tenant_id, instance_no, trigger_type, trigger_request_id, created_at
FROM archive.job_instance_archive
WHERE trigger_request_id IS NULL
  AND trigger_type <> 'MANUAL'
ORDER BY created_at, id;

DO $$
DECLARE
    hot_violations integer;
    archive_violations integer;
BEGIN
    SELECT count(*) INTO hot_violations
    FROM batch.job_instance
    WHERE trigger_request_id IS NULL
      AND trigger_type <> 'MANUAL';

    SELECT count(*) INTO archive_violations
    FROM archive.job_instance_archive
    WHERE trigger_request_id IS NULL
      AND trigger_type <> 'MANUAL';

    IF hot_violations > 0 OR archive_violations > 0 THEN
        RAISE EXCEPTION
            'V136/V137 preflight failed: batch.job_instance violations=%, archive.job_instance_archive violations=%',
            hot_violations,
            archive_violations;
    END IF;
END $$;
