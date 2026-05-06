-- =====================================================================
-- V114: disaster_day_override 灾难日热切换（ADR-023 Stage 4）
-- =====================================================================
-- 背景:
--   突发停业需要把当天标 SKIP 但又想保留原 holiday 配置 — 当前只能改
--   holiday 表，影响重放 + 审计混淆。
--
-- 决策（ADR-023 §决策 §灾难日热切换）:
--   独立 disaster_day_override 表；BatchDayOpenScheduler 创建批量日前
--   先查这张表；命中即按 action 处理（直接 SKIPPED 或 DEFER 到次日）；
--   不改 holiday 表，事后审计清晰。
--
-- 字段:
--   action       SKIP                  - 直接 SKIPPED，不开批量日
--                DEFER_TO_NEXT_BIZDAY  - 推迟到次日开（v1 仅日志记，
--                                          实际等次日自然推进）
--   reason       自由文本，必填
--   approved_by  审批人 ID（必经审批，ADR-023 §开放问题 #4）
--   approved_at  审批时刻
--   effective_at 生效时刻（≤ now 才算激活）
--   ttl_until    失效时刻（> now 才算激活）
--
-- 不变量:
--   同 (tenant, calendar, biz_date) 在 ttl 期内只能有 1 条 active —
--   partial unique index（ttl_until > now 时唯一）
-- =====================================================================

CREATE TABLE IF NOT EXISTS batch.disaster_day_override (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    calendar_code   VARCHAR(128) NOT NULL,
    biz_date        DATE         NOT NULL,
    action          VARCHAR(32)  NOT NULL,
    reason          VARCHAR(1024) NOT NULL,
    approved_by     VARCHAR(128) NOT NULL,
    approved_at     TIMESTAMPTZ  NOT NULL,
    effective_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ttl_until       TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_disaster_action
        CHECK (action IN ('SKIP', 'DEFER_TO_NEXT_BIZDAY')),
    CONSTRAINT ck_disaster_ttl_after_effective
        CHECK (ttl_until > effective_at)
);

-- 同 (tenant, calendar, biz_date) 在未过期 ttl 期内至多一条 active
CREATE UNIQUE INDEX IF NOT EXISTS uk_disaster_active
    ON batch.disaster_day_override (tenant_id, calendar_code, biz_date)
    WHERE ttl_until > CURRENT_TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_disaster_lookup
    ON batch.disaster_day_override (tenant_id, calendar_code, biz_date, ttl_until);

COMMENT ON TABLE batch.disaster_day_override IS
    'ADR-023 灾难日热切换：突发停业不改 holiday 表，独立审计行';
COMMENT ON COLUMN batch.disaster_day_override.action IS
    'SKIP: 当日 SKIPPED 不开批量日; DEFER_TO_NEXT_BIZDAY: 推迟到次日';
