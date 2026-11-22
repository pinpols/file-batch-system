-- =====================================================================
-- V105: batch_day_operation_audit 独立审计表
-- =====================================================================
-- 背景:
--   §14.3 P1 — 当前批量日治理操作审计写到 job_execution_log 复用,
--   不便按 (calendar_code, biz_date) 索引检索, 且 Console 操作历史 UI
--   需要稳定的实体表。本表将批量日维度高风险动作沉淀为一等表。
--
-- 写入路径:
--   BatchDayOperationService 在 FREEZE / RELEASE / SKIP / REOPEN /
--   CLOSE / CATCH_UP 等动作完成后, 与状态 update 同事务插入一行,
--   保留对 job_execution_log 的兼容写以兼容历史排查链路。
--
-- 字段:
--   tenant_id        必填; 多租隔离主键之一
--   calendar_code    必填
--   biz_date         必填
--   operation_type   BATCH_DAY_FREEZE / RELEASE / SKIP / REOPEN /
--                    CLOSE / OPEN / CUTOFF / CATCH_UP / ...
--   from_status      变更前 day_status (含 frozen 标记)
--   to_status        变更后 day_status
--   from_frozen      变更前 frozen 标记
--   to_frozen        变更后 frozen 标记
--   operator_id      操作人 ID (REQUEST 来源 / SYSTEM)
--   operator_type    REQUEST / SYSTEM
--   reason_code      用户提交的原因码 (HOLIDAY / OPS_ISSUE 等)
--   comment          自由文本(<=1024)
--   approval_id      关联审批流 ID, 后续接审批时使用
--   request_payload  操作请求快照 (JSONB), 便于排障/重放
--   trace_id         请求链路 ID
--   created_at       审计时刻
-- =====================================================================

CREATE TABLE IF NOT EXISTS batch.batch_day_operation_audit (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    calendar_code   VARCHAR(128) NOT NULL,
    biz_date        DATE         NOT NULL,
    operation_type  VARCHAR(64)  NOT NULL,
    from_status     VARCHAR(32),
    to_status       VARCHAR(32),
    from_frozen     BOOLEAN,
    to_frozen       BOOLEAN,
    operator_id     VARCHAR(128) NOT NULL,
    operator_type   VARCHAR(32)  NOT NULL,
    reason_code     VARCHAR(128),
    comment         VARCHAR(1024),
    approval_id     BIGINT,
    request_payload JSONB,
    trace_id        VARCHAR(128),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_batch_day_op_audit_tenant_calendar_date
    ON batch.batch_day_operation_audit (tenant_id, calendar_code, biz_date, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_batch_day_op_audit_tenant_op_date
    ON batch.batch_day_operation_audit (tenant_id, operation_type, created_at DESC);

COMMENT ON TABLE batch.batch_day_operation_audit IS
    '批量日维度高风险治理操作审计 — Console 操作历史与运维查询的权威源';
COMMENT ON COLUMN batch.batch_day_operation_audit.operation_type IS
    'BATCH_DAY_FREEZE / RELEASE / SKIP / REOPEN / CLOSE / OPEN / CUTOFF / CATCH_UP 等';
COMMENT ON COLUMN batch.batch_day_operation_audit.operator_type IS
    'REQUEST(API/Console) / SYSTEM(scheduler/触发兜底)';
COMMENT ON COLUMN batch.batch_day_operation_audit.request_payload IS
    '操作请求快照 (JSONB) — 用于排障/重放';
