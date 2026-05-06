-- =====================================================================
-- V116: ADR-022 v0.1 — Forensic 取证日志（精简版）
-- =====================================================================
-- 范围（v0.1，§14.3.2 P1 简化版）:
--   只做"按 (tenantId, bizDate) 一键打包导出"的元数据追踪表。
--   不做：*_history 影子表（推迟到 v0.2）/ OSS 对象锁（v0.2）/
--          trace 长保留（v0.3）/ 跨系统统一日志平台（不属本 ADR）。
--
-- 设计:
--   1 行 = 一次 forensic export 请求；service 同步打包，
--   完成后回写 status=COMPLETED + storage_path + sha256。
--
-- 兼容:
--   零业务影响 — 此表不在主链路（trigger / claim / report）写入路径。
-- =====================================================================

CREATE TABLE IF NOT EXISTS batch.forensic_export_log (
    id                 BIGSERIAL PRIMARY KEY,
    tenant_id          VARCHAR(64)  NOT NULL,
    export_id          VARCHAR(128) NOT NULL,
    biz_date_from      DATE         NOT NULL,
    biz_date_to        DATE         NOT NULL,
    job_codes          JSONB,                 -- ["JOB_A", "JOB_B"] 可选过滤; null = 全部
    scope              JSONB        NOT NULL, -- ["job_instances","files","retries",...]
    export_format      VARCHAR(32)  NOT NULL, -- BUNDLE / JSON / CSV
    status             VARCHAR(32)  NOT NULL, -- PROCESSING / COMPLETED / FAILED
    storage_path       VARCHAR(1024),         -- 本地 fs 路径或 OSS URI
    file_size_bytes    BIGINT,
    sha256             VARCHAR(64),
    row_counts         JSONB,                 -- 每个 scope 实际导出行数
    error_message      VARCHAR(2048),
    requested_by       VARCHAR(128) NOT NULL,
    requested_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at       TIMESTAMPTZ,
    retention_until    DATE,                  -- v0.2 OSS 接入后才填
    trace_id           VARCHAR(128),
    UNIQUE (tenant_id, export_id)
);

CREATE INDEX IF NOT EXISTS idx_forensic_export_log_tenant_status
    ON batch.forensic_export_log (tenant_id, status, requested_at DESC);

CREATE INDEX IF NOT EXISTS idx_forensic_export_log_tenant_bizdate
    ON batch.forensic_export_log (tenant_id, biz_date_from, biz_date_to, requested_at DESC);

ALTER TABLE batch.forensic_export_log DROP CONSTRAINT IF EXISTS ck_forensic_export_status;
ALTER TABLE batch.forensic_export_log ADD CONSTRAINT ck_forensic_export_status
    CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED'));

ALTER TABLE batch.forensic_export_log DROP CONSTRAINT IF EXISTS ck_forensic_export_format;
ALTER TABLE batch.forensic_export_log ADD CONSTRAINT ck_forensic_export_format
    CHECK (export_format IN ('BUNDLE', 'JSON', 'CSV'));

ALTER TABLE batch.forensic_export_log DROP CONSTRAINT IF EXISTS ck_forensic_export_dates;
ALTER TABLE batch.forensic_export_log ADD CONSTRAINT ck_forensic_export_dates
    CHECK (biz_date_from <= biz_date_to);

COMMENT ON TABLE batch.forensic_export_log IS
    'ADR-022 v0.1 forensic 取证导出元数据 — 一行 = 一次 export 请求';
COMMENT ON COLUMN batch.forensic_export_log.scope IS
    'JSONB 数组: ["job_instances","files","retries","approvals","operations","audits"] 等';
COMMENT ON COLUMN batch.forensic_export_log.row_counts IS
    'JSONB: {"job_instances": 12, "files": 5, "retries": 2, ...} 实际导出行数, 便于核对';
COMMENT ON COLUMN batch.forensic_export_log.sha256 IS
    'export bundle 的 SHA-256 hex; 监管复盘验证完整性';
COMMENT ON COLUMN batch.forensic_export_log.retention_until IS
    'v0.1 留 NULL; v0.2 接 OSS 后由 retention 策略填 (默认 7 年)';
