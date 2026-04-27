-- =========================================================
-- batch_business.biz business tables (import/export demo model)
-- 非 Flyway：由以下三种方式执行，哪种都要保证最终落在 batch_business 库。
--
-- ⚠️  目标库 = batch_business（不是 batch_platform）
--     在 batch_platform 库直接执行会创建出 batch_platform.biz 污染 schema，
--     业务代码不会读它（platform / business 两个 DataSource 物理分离），
--     但日积月累会让人误以为业务数据在主库 ——  2026-04-25 已清理过一次。
--
-- 三种正确执行路径：
--   1) E2E test：Spring @Sql 走主 DataSource。application-e2e.yml 显式把 business
--      DS 映射到主 DS（${spring.datasource.url}），所以测试期间会落在 testcontainer
--      的 batch_platform.biz —— 这是测试简化的有意设计（单 PG 容器，跑完丢弃）。
--   2) 联调灌种：scripts/data/load-system-test-data.sh 用 psql_business（已 -d
--      batch_business），直接跑该脚本即可，无需手动切库。
--   3) 运维手工：必须 `psql -d batch_business -f scripts/db/business/create_biz_tables.sql`，
--      不要在 batch_platform 会话里 \i 该文件。
--
-- Scope:
-- 1) customer import target table
-- 2) settlement export source tables
-- 3) tb transaction import target（IMP-TRANSACTION-CSV）
-- 4) tc risk_score import target（IMP-RISK-SCORE-JSON）
-- 5) tc risk_alert export source（EXP-RISK-ALERT-JSON）
-- =========================================================

CREATE SCHEMA IF NOT EXISTS biz;
CREATE SCHEMA IF NOT EXISTS batch;

-- PROCESS WAP staging table lives with PROCESS business source/target tables.
-- sqlTransformCompute uses processBusinessDataSource for source, staging and target so
-- INSERT ... SELECT ... COMMIT can stay inside one physical database.
CREATE TABLE IF NOT EXISTS batch.process_staging (
    batch_key      TEXT        NOT NULL,
    row_seq        BIGSERIAL   NOT NULL,
    tenant_id      TEXT        NOT NULL,
    target_schema  TEXT        NOT NULL,
    target_table   TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    staged_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (batch_key, row_seq)
);

CREATE INDEX IF NOT EXISTS idx_process_staging_batch_key
    ON batch.process_staging (batch_key);
CREATE INDEX IF NOT EXISTS idx_process_staging_tenant_batch
    ON batch.process_staging (tenant_id, batch_key);
CREATE INDEX IF NOT EXISTS idx_process_staging_target_batch
    ON batch.process_staging (tenant_id, target_schema, target_table, batch_key);
CREATE INDEX IF NOT EXISTS idx_process_staging_staged_at
    ON batch.process_staging (staged_at);

CREATE TABLE IF NOT EXISTS biz.customer_account (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    customer_no       VARCHAR(64)  NOT NULL,
    customer_name     VARCHAR(256) NOT NULL,
    customer_type     VARCHAR(32)  NOT NULL DEFAULT 'ENTERPRISE',
    certificate_no    VARCHAR(128),
    mobile_no         VARCHAR(32),
    email             VARCHAR(256),
    status            VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    source_file_name  VARCHAR(512),
    source_batch_no   VARCHAR(128),
    source_trace_id   VARCHAR(128),
    created_by        VARCHAR(64),
    updated_by        VARCHAR(64),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_customer_account_tenant_no UNIQUE (tenant_id, customer_no),
    CONSTRAINT ck_customer_account_type CHECK (customer_type IN ('PERSONAL', 'ENTERPRISE')),
    CONSTRAINT ck_customer_account_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'FROZEN'))
);

CREATE INDEX IF NOT EXISTS idx_customer_account_status
    ON biz.customer_account (tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_customer_account_name
    ON biz.customer_account (tenant_id, customer_name);

CREATE TABLE IF NOT EXISTS biz.settlement_batch (
    id                   BIGSERIAL PRIMARY KEY,
    tenant_id            VARCHAR(64)  NOT NULL,
    batch_no             VARCHAR(128) NOT NULL,
    biz_date             DATE         NOT NULL,
    accounting_period    VARCHAR(32)  NOT NULL,
    snapshot_mode        VARCHAR(32)  NOT NULL DEFAULT 'BIZ_DATE',
    snapshot_ts          TIMESTAMPTZ,
    source_partitions    JSONB,
    consistency_policy   VARCHAR(32)  NOT NULL DEFAULT 'EXPORT_SNAPSHOT',
    batch_status         VARCHAR(32)  NOT NULL DEFAULT 'READY',
    total_record_count   INTEGER      NOT NULL DEFAULT 0,
    total_amount         NUMERIC(18, 2) NOT NULL DEFAULT 0,
    currency             VARCHAR(16)  NOT NULL DEFAULT 'CNY',
    created_by           VARCHAR(64),
    updated_by           VARCHAR(64),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_settlement_batch_tenant_batch_no UNIQUE (tenant_id, batch_no),
    CONSTRAINT ck_settlement_batch_snapshot_mode CHECK (snapshot_mode IN ('BIZ_DATE', 'PERIOD', 'BATCH', 'SNAPSHOT_TS')),
    CONSTRAINT ck_settlement_batch_consistency_policy CHECK (consistency_policy IN ('REPEATABLE_READ', 'EXPORT_SNAPSHOT', 'MATERIALIZED_STAGE')),
    CONSTRAINT ck_settlement_batch_status CHECK (batch_status IN ('READY', 'RUNNING', 'EXPORTED', 'ARCHIVED', 'CANCELLED')),
    CONSTRAINT ck_settlement_batch_total_record_count CHECK (total_record_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_settlement_batch_biz_date
    ON biz.settlement_batch (tenant_id, biz_date, batch_status);
CREATE INDEX IF NOT EXISTS idx_settlement_batch_period
    ON biz.settlement_batch (tenant_id, accounting_period);

CREATE TABLE IF NOT EXISTS biz.settlement_detail (
    id                   BIGSERIAL PRIMARY KEY,
    tenant_id            VARCHAR(64)  NOT NULL,
    batch_id             BIGINT       NOT NULL REFERENCES biz.settlement_batch(id),
    settlement_no        VARCHAR(128) NOT NULL,
    customer_no          VARCHAR(64)  NOT NULL,
    biz_date             DATE         NOT NULL,
    accounting_period    VARCHAR(32)  NOT NULL,
    order_no             VARCHAR(128),
    gross_amount         NUMERIC(18, 2) NOT NULL DEFAULT 0,
    fee_amount           NUMERIC(18, 2) NOT NULL DEFAULT 0,
    net_amount           NUMERIC(18, 2) NOT NULL DEFAULT 0,
    currency             VARCHAR(16)  NOT NULL DEFAULT 'CNY',
    settlement_status    VARCHAR(32)  NOT NULL DEFAULT 'READY',
    exported_version     INTEGER      NOT NULL DEFAULT 0,
    source_trace_id      VARCHAR(128),
    created_by           VARCHAR(64),
    updated_by           VARCHAR(64),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_settlement_detail_tenant_no UNIQUE (tenant_id, settlement_no),
    CONSTRAINT ck_settlement_detail_gross_amount CHECK (gross_amount >= 0),
    CONSTRAINT ck_settlement_detail_fee_amount CHECK (fee_amount >= 0),
    CONSTRAINT ck_settlement_detail_net_amount CHECK (net_amount >= 0),
    CONSTRAINT ck_settlement_detail_exported_version CHECK (exported_version >= 0),
    CONSTRAINT ck_settlement_detail_status CHECK (settlement_status IN ('READY', 'SETTLED', 'EXPORTED', 'FAILED', 'REVERSED'))
);

CREATE INDEX IF NOT EXISTS idx_settlement_detail_batch
    ON biz.settlement_detail (batch_id);
CREATE INDEX IF NOT EXISTS idx_settlement_detail_biz_date
    ON biz.settlement_detail (tenant_id, biz_date, settlement_status);
CREATE INDEX IF NOT EXISTS idx_settlement_detail_customer
    ON biz.settlement_detail (tenant_id, customer_no, accounting_period);

-- ---------------------------------------------------------
-- tb 交易流水导入目标表（对应 tb/IMP-TRANSACTION-CSV 模板）
-- 列名与模板 field_mappings.targetColumn 对齐；conflictColumns = (tenant_id, txn_no)
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS biz.transaction (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64)   NOT NULL,
    txn_no          VARCHAR(64)   NOT NULL,
    account_no      VARCHAR(64)   NOT NULL,
    txn_type        VARCHAR(32)   NOT NULL,
    amount          NUMERIC(20, 2) NOT NULL,
    currency_code   VARCHAR(8)    NOT NULL DEFAULT 'CNY',
    txn_date        DATE          NOT NULL,
    remark          VARCHAR(512),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_transaction_tenant_txn UNIQUE (tenant_id, txn_no),
    CONSTRAINT ck_transaction_amount CHECK (amount >= 0)
);

CREATE INDEX IF NOT EXISTS idx_transaction_tenant_date
    ON biz.transaction (tenant_id, txn_date);
CREATE INDEX IF NOT EXISTS idx_transaction_account
    ON biz.transaction (tenant_id, account_no);

-- ---------------------------------------------------------
-- tc 风险评分导入目标表（对应 tc/IMP-RISK-SCORE-JSON 模板）
-- conflictColumns = (tenant_id, entity_id, score_date)
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS biz.risk_score (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64)   NOT NULL,
    entity_id       VARCHAR(64)   NOT NULL,
    entity_type     VARCHAR(32)   NOT NULL,
    score_value     NUMERIC(10, 2) NOT NULL,
    score_band      VARCHAR(16)   NOT NULL,
    score_date      DATE          NOT NULL,
    model_version   VARCHAR(32),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_risk_score_tenant_entity_date UNIQUE (tenant_id, entity_id, score_date),
    CONSTRAINT ck_risk_score_band
        CHECK (score_band IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

CREATE INDEX IF NOT EXISTS idx_risk_score_tenant_date
    ON biz.risk_score (tenant_id, score_date);
CREATE INDEX IF NOT EXISTS idx_risk_score_band
    ON biz.risk_score (tenant_id, score_band, score_date);

-- ---------------------------------------------------------
-- tc 风险预警导出源表（对应 tc/EXP-RISK-ALERT-JSON 模板）
-- default_query_sql 从本表按 tenant_id 拉取并 ORDER BY id
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS biz.risk_alert (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64)   NOT NULL,
    alert_id        VARCHAR(64)   NOT NULL,
    entity_id       VARCHAR(64)   NOT NULL,
    alert_type      VARCHAR(32)   NOT NULL,
    severity        VARCHAR(16)   NOT NULL,
    alert_date      DATE          NOT NULL,
    description     VARCHAR(512),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_risk_alert_tenant_alert UNIQUE (tenant_id, alert_id),
    CONSTRAINT ck_risk_alert_severity
        CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

CREATE INDEX IF NOT EXISTS idx_risk_alert_tenant_date
    ON biz.risk_alert (tenant_id, alert_date);
CREATE INDEX IF NOT EXISTS idx_risk_alert_entity
    ON biz.risk_alert (tenant_id, entity_id, alert_date);

-- ---------------------------------------------------------
-- PROCESS WAP demo: order event source + account summary target.
-- 用于 sqlTransformCompute e2e:聚合 process_order_event → process_account_summary。
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS biz.process_order_event (
    tenant_id   VARCHAR(32)    NOT NULL,
    account_id  VARCHAR(32)    NOT NULL,
    biz_date    DATE           NOT NULL,
    event_id    BIGINT         NOT NULL,
    amount      NUMERIC(18, 2) NOT NULL
);

CREATE TABLE IF NOT EXISTS biz.process_account_summary (
    tenant_id        VARCHAR(32)    NOT NULL,
    account_id       VARCHAR(32)    NOT NULL,
    biz_date         DATE           NOT NULL,
    total_amount     NUMERIC(18, 2) NOT NULL,
    high_water_mark  BIGINT         NOT NULL,
    PRIMARY KEY (tenant_id, account_id, biz_date)
);
