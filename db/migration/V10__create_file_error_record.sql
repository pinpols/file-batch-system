-- =========================================================
-- V9 - 创建文件错误记录表
-- 说明：
-- 1) 持久化导入/导出记录级错误，供控制台查询和重放。
-- 2) 将 raw_record 保存为 JSONB，便于排查和运维复核。
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.file_error_record (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    file_id             BIGINT,
    pipeline_instance_id BIGINT,
    pipeline_step_run_id BIGINT,
    record_no           BIGINT,
    error_code          VARCHAR(128) NOT NULL,
    error_message       VARCHAR(1024),
    error_stage         VARCHAR(64),
    is_skipped          BOOLEAN NOT NULL DEFAULT FALSE,
    skip_action         VARCHAR(32),
    raw_record          JSONB,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_file_error_record_tenant_file
    ON batch.file_error_record (tenant_id, file_id, error_stage, error_code);

CREATE INDEX IF NOT EXISTS idx_file_error_record_created_at
    ON batch.file_error_record (created_at DESC);
