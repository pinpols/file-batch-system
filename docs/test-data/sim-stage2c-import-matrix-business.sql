-- Stage 2c Import load-mode 矩阵的业务 fixture。

CREATE TABLE IF NOT EXISTS biz.import_stage2c_customer (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    customer_no       VARCHAR(64)  NOT NULL,
    customer_name     VARCHAR(256) NOT NULL,
    customer_type     VARCHAR(32)  NOT NULL,
    certificate_no    VARCHAR(128),
    mobile_no         VARCHAR(32),
    email             VARCHAR(256),
    status            VARCHAR(32)  NOT NULL,
    source_file_name  VARCHAR(512),
    source_batch_no   VARCHAR(128),
    source_trace_id   VARCHAR(128),
    created_by        VARCHAR(64),
    updated_by        VARCHAR(64),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_import_stage2c_customer_batch
    ON biz.import_stage2c_customer (tenant_id, source_batch_no);

DELETE FROM biz.import_stage2c_customer
WHERE tenant_id = 'ta'
  AND (customer_no LIKE 'S2CAPP%' OR customer_no LIKE 'S2CREP%');

DELETE FROM biz.customer_account
WHERE tenant_id = 'ta'
  AND customer_no LIKE 'S2CUPS%';
