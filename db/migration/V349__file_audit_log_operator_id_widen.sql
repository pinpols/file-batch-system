-- =========================================================
-- V349：放宽文件审计的操作主体标识长度。
--
-- 原 V6 中 batch.file_audit_log.operator_id 为 VARCHAR(64)，实际主体标识（用户 / Worker /
-- API 调用方）可能超长。已提交迁移不可改写（会改变 Flyway checksum），故以增量迁移放宽，
-- 并保留列注释。
-- =========================================================

-- PostgreSQL 对 VARCHAR 加长只改元数据、不重写表；短的 ACCESS EXCLUSIVE 由迁移窗口覆盖，
-- 与仓库既有 squawk-ignore 用法一致。
ALTER TABLE batch.file_audit_log
    -- squawk-ignore changing-column-type
    ALTER COLUMN operator_id TYPE VARCHAR(128);

COMMENT ON COLUMN batch.file_audit_log.operator_id IS
    '文件操作主体标识，例如用户或 Worker。';
