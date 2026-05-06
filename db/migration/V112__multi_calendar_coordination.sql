-- =====================================================================
-- V112: 多日历联动 + 半天工作日（ADR-023 Stages 1+2）
-- =====================================================================
-- 背景:
--   §14.3.2 设计层缺口"多日历联动 + 半天 cutoff"。当前 business_calendar
--   独立配置：单 timezone + 单 cutoff_time + 单租户独立 holiday 表，
--   跨境 (CN/HK/US) 联动 / 半天 cutoff (圣诞夜 13:00) / 共享假日 (亚洲
--   市场都关春节) 都无法表达。
--
-- 决策（ADR-023 §决策 Stages 1+2）:
--   1. calendar_group：多 calendar 共享假日的命名空间；
--   2. business_calendar.group_code：可选加入 group；
--   3. business_calendar.cutoff_schedule：JSONB 替代单值 cutoff_time，
--      支持 default + overrides（按日期 / weekdayPattern）；
--   4. calendar_holiday.scope + group_code：CALENDAR / GROUP 两级，
--      解析层先 CALENDAR 命中再 fallback 到同 group 的 GROUP 行。
--
-- 后续阶段（不在 V112 范围）:
--   Stage 3: calendar_dependency 串联（需另一 migration）
--   Stage 4: disaster_day_override（需另一 migration）
-- =====================================================================

-- ── Stage 1: calendar_group + holiday.scope/group_code ─────────────────
CREATE TABLE IF NOT EXISTS batch.calendar_group (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,
    group_code  VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_calendar_group_tenant_code UNIQUE (tenant_id, group_code)
);

COMMENT ON TABLE batch.calendar_group IS
    'ADR-023 calendar 命名空间：多 calendar 共享假日 (calendar_holiday.scope=GROUP) 的归属组';

ALTER TABLE batch.business_calendar
    ADD COLUMN IF NOT EXISTS group_code VARCHAR(128);
COMMENT ON COLUMN batch.business_calendar.group_code IS
    'ADR-023 加入哪个 calendar_group；NULL = 不加入任何组（行为同前）';

ALTER TABLE batch.calendar_holiday
    ADD COLUMN IF NOT EXISTS scope VARCHAR(32) NOT NULL DEFAULT 'CALENDAR',
    ADD COLUMN IF NOT EXISTS group_code VARCHAR(128);

ALTER TABLE batch.calendar_holiday DROP CONSTRAINT IF EXISTS ck_calendar_holiday_scope;
ALTER TABLE batch.calendar_holiday ADD CONSTRAINT ck_calendar_holiday_scope
    CHECK (scope IN ('CALENDAR', 'GROUP'));

-- GROUP scope 行不绑 calendar_id；为保留 calendar_id NOT NULL 兼容性，
-- 让 GROUP 行的 calendar_id 指向同 group 的"代表 calendar"（约定第一个加入组的）。
-- 若 GROUP 行无 calendar_id 强制需求，未来 minor migration 改 nullable。

CREATE INDEX IF NOT EXISTS idx_calendar_holiday_scope_group
    ON batch.calendar_holiday (scope, group_code, biz_date)
    WHERE scope = 'GROUP';

COMMENT ON COLUMN batch.calendar_holiday.scope IS
    'ADR-023 假日作用域：CALENDAR (单 calendar) / GROUP (整组共享)';
COMMENT ON COLUMN batch.calendar_holiday.group_code IS
    'ADR-023 scope=GROUP 时所属 calendar_group.group_code；scope=CALENDAR 时 NULL';

-- ── Stage 2: cutoff_schedule JSONB ────────────────────────────────────
ALTER TABLE batch.business_calendar
    ADD COLUMN IF NOT EXISTS cutoff_schedule JSONB;

COMMENT ON COLUMN batch.business_calendar.cutoff_schedule IS
    'ADR-023 半天 / 多 cutoff schedule，JSONB { "default": "06:00", "overrides": [...] }; NULL = 用 cutoff_time 单值（向后兼容）';

-- 注：archive.business_calendar / archive.calendar_holiday 不在 V71 14
--     张归档对照表内（定义表不入归档），无需 archive 镜像 ALTER。
