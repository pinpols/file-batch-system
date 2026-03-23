CREATE SCHEMA IF NOT EXISTS biz;

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
