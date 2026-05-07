-- =====================================================================
-- V118: ADR-021 数据对账闭环 v1.0 — 主模型 + ADR-017 EFFECTIVE 链 gate
-- =====================================================================
-- 范围红线（priority-scope §ADR-021）：
--   ✅ 做：批量交付闭环对账（行 / 表级 / 跨表 / 跨日）
--   ❌ 不做：主数据治理 / 财务核算 / 数据血缘 / 跨系统业务语义仲裁
--
-- 判定提问：「这条规则的失败结果，是修业务数据还是裁定业务对错？」
--   修业务数据 → 属本 ADR；裁定业务对错 → 不属本 ADR。
-- =====================================================================

-- ── data_quality_rule（DQ 规则定义） ──────────────────────────────────
CREATE TABLE IF NOT EXISTS batch.data_quality_rule (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    rule_code           VARCHAR(128) NOT NULL,
    rule_name           VARCHAR(256) NOT NULL,
    rule_type           VARCHAR(32)  NOT NULL,     -- ROW_LEVEL / TABLE_LEVEL / CROSS_TABLE / CROSS_DAY
    scope_business_key  VARCHAR(256) NOT NULL,     -- 关联 result_version.business_key（如 job:DAILY_PNL:）
    expression          TEXT         NOT NULL,     -- 规则表达式（SQL / JSONLogic / SPI ref）
    threshold_json      JSONB,                     -- {maxFailRows, failRatio, deltaTolerance...}
    severity            VARCHAR(16)  NOT NULL DEFAULT 'WARN',  -- BLOCKER / WARN / INFO
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    description         VARCHAR(512),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_dq_rule_tenant_code UNIQUE (tenant_id, rule_code),
    CONSTRAINT ck_dq_rule_type
        CHECK (rule_type IN ('ROW_LEVEL', 'TABLE_LEVEL', 'CROSS_TABLE', 'CROSS_DAY')),
    CONSTRAINT ck_dq_rule_severity
        CHECK (severity IN ('BLOCKER', 'WARN', 'INFO'))
);

CREATE INDEX IF NOT EXISTS idx_dq_rule_scope
    ON batch.data_quality_rule (tenant_id, scope_business_key, enabled);

COMMENT ON TABLE batch.data_quality_rule IS
    'ADR-021 数据对账规则定义；scope_business_key 前缀匹配 result_version.business_key';
COMMENT ON COLUMN batch.data_quality_rule.severity IS
    'BLOCKER 失败阻塞 EFFECTIVE 链路（强制 MANUAL_APPROVAL）；WARN 仅记录；INFO 仅观测';

-- ── data_quality_check（一次 job 的 DQ 检查实例） ────────────────────
CREATE TABLE IF NOT EXISTS batch.data_quality_check (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    job_instance_id     BIGINT       NOT NULL,
    rule_id             BIGINT,                     -- NULL = SPI sink 写入，无规则 id 关联
    rule_code           VARCHAR(128) NOT NULL,      -- 冗余 rule_code 让 audit 可读
    rule_type           VARCHAR(32)  NOT NULL,
    severity            VARCHAR(16)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,      -- PASS / WARN / FAIL / SKIPPED / ERROR
    metrics_json        JSONB,                      -- 命中数 / 比例 / 偏差等
    failure_sample      JSONB,                      -- 前 N 条失败样本（限 50 条 / 64KB）
    error_message       VARCHAR(2048),              -- 规则执行失败时的错误信息
    checked_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_dq_check_status
        CHECK (status IN ('PASS', 'WARN', 'FAIL', 'SKIPPED', 'ERROR'))
);

CREATE INDEX IF NOT EXISTS idx_dq_check_instance
    ON batch.data_quality_check (tenant_id, job_instance_id);
CREATE INDEX IF NOT EXISTS idx_dq_check_rule_status
    ON batch.data_quality_check (tenant_id, rule_id, status, checked_at DESC);

COMMENT ON TABLE batch.data_quality_check IS
    'ADR-021 单次 job_instance 的对账检查结果；多条 rule 并行写多行';

-- ── archive 镜像（V71 14 张归档对照表 + 本次新增 2 张，archive drift check 自动覆盖） ──
CREATE TABLE IF NOT EXISTS archive.data_quality_rule_archive (
    LIKE batch.data_quality_rule INCLUDING DEFAULTS INCLUDING CONSTRAINTS
);
CREATE TABLE IF NOT EXISTS archive.data_quality_check_archive (
    LIKE batch.data_quality_check INCLUDING DEFAULTS INCLUDING CONSTRAINTS
);

-- ── result_version 加 dq_gate_status 列：标记 EFFECTIVE 推进时 DQ 是否过关 ──
ALTER TABLE batch.result_version
    ADD COLUMN IF NOT EXISTS dq_gate_status VARCHAR(16);

COMMENT ON COLUMN batch.result_version.dq_gate_status IS
    'ADR-021 DQ gate 结果：PASS / WARN / BLOCKED；BLOCKED → promotion_policy 强制 MANUAL_APPROVAL';

ALTER TABLE archive.result_version_archive
    ADD COLUMN IF NOT EXISTS dq_gate_status VARCHAR(16);
