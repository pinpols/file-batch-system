-- =====================================================================
-- V113: calendar_dependency 跨日历串联（ADR-023 Stage 3）
-- =====================================================================
-- 背景:
--   跨境业务（中国 + 香港 + 美国）需要"中国 calendar SETTLED 后才起香港
--   calendar"；当前 batch_day_instance 各 calendar 独立 open，无法表达。
--
-- 决策（ADR-023 §决策 §Calendar Dependency）:
--   batch.calendar_dependency 描述 (upstream_code → downstream_code) 的
--   等待规则；BatchDayOpenScheduler 创建 downstream 批量日前查 dependency，
--   未满足 → 推迟（不算失败，写 BLOCKED_BY_UPSTREAM_CALENDAR 原因）。
--
-- 字段:
--   upstream_code   必须先到指定状态的上游 calendar
--   downstream_code 等待 upstream 的下游 calendar
--   rule            WAIT_SETTLED   - upstream batch_day_instance.day_status='SETTLED'
--                   WAIT_CUTOFF    - upstream cutoff_at 已过
--                   SAME_DAY_PARALLEL - 同 bizDate 并行（占位，v1 不强校验）
--
-- 不变量:
--   不允许成环 — orchestrator 启动期 graph validator 兜底（DependencyCycleCheck）
-- =====================================================================

CREATE TABLE IF NOT EXISTS batch.calendar_dependency (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    upstream_code   VARCHAR(128) NOT NULL,
    downstream_code VARCHAR(128) NOT NULL,
    rule            VARCHAR(32)  NOT NULL DEFAULT 'WAIT_SETTLED',
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    description     VARCHAR(512),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_calendar_dependency UNIQUE (tenant_id, upstream_code, downstream_code),
    CONSTRAINT ck_calendar_dependency_rule
        CHECK (rule IN ('WAIT_SETTLED', 'WAIT_CUTOFF', 'SAME_DAY_PARALLEL')),
    CONSTRAINT ck_calendar_dependency_no_self_loop
        CHECK (upstream_code <> downstream_code)
);

CREATE INDEX IF NOT EXISTS idx_calendar_dependency_downstream
    ON batch.calendar_dependency (tenant_id, downstream_code, enabled);

COMMENT ON TABLE batch.calendar_dependency IS
    'ADR-023 跨 calendar 串联依赖：A SETTLED 才起 B（中港美等跨境联动）';
COMMENT ON COLUMN batch.calendar_dependency.rule IS
    'WAIT_SETTLED: upstream batch_day_instance.day_status=SETTLED;'
    ' WAIT_CUTOFF: upstream cutoff_at 已过;'
    ' SAME_DAY_PARALLEL: 同日并行（占位）';
