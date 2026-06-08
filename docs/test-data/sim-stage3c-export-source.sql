-- Stage 3c Export source data for 8-shard and multi-tenant smoke.
-- Required psql variable: batch_no

INSERT INTO biz.customer_account (
    tenant_id, customer_no, customer_name, customer_type, certificate_no,
    mobile_no, email, status, source_file_name, source_batch_no, source_trace_id,
    created_by, updated_by
)
SELECT 'ta',
       'EXP3B-' || lpad(gs::text, 4, '0'),
       'Export Stage3c ' || gs,
       'PERSONAL',
       'EXP3CCERT' || lpad(gs::text, 4, '0'),
       '1392300' || lpad(gs::text, 4, '0'),
       'exp3c' || gs || '@sim.io',
       CASE WHEN gs % 4 = 0 THEN 'INACTIVE' ELSE 'ACTIVE' END,
       'stage3c-export-seed.csv',
       :'batch_no',
       'stage3c-export-seed',
       'sim-e2e',
       'sim-e2e'
FROM generate_series(1, 80) AS gs
ON CONFLICT (tenant_id, customer_no) DO UPDATE
SET customer_name = EXCLUDED.customer_name,
    customer_type = EXCLUDED.customer_type,
    certificate_no = EXCLUDED.certificate_no,
    mobile_no = EXCLUDED.mobile_no,
    email = EXCLUDED.email,
    status = EXCLUDED.status,
    source_file_name = EXCLUDED.source_file_name,
    source_batch_no = EXCLUDED.source_batch_no,
    source_trace_id = EXCLUDED.source_trace_id,
    updated_by = EXCLUDED.updated_by,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO biz.transaction (
    tenant_id, txn_no, account_no, txn_type, amount, currency_code, txn_date, remark
)
SELECT 'tb',
       'EXP3C-TXN-' || lpad(gs::text, 4, '0'),
       'ACCT-' || lpad((gs % 5 + 1)::text, 3, '0'),
       CASE WHEN gs % 2 = 0 THEN 'DEBIT' ELSE 'CREDIT' END,
       (100 + gs)::numeric(20, 2),
       'CNY',
       CURRENT_DATE,
       'stage3c export source ' || :'batch_no'
FROM generate_series(1, 20) AS gs
ON CONFLICT (tenant_id, txn_no) DO UPDATE
SET account_no = EXCLUDED.account_no,
    txn_type = EXCLUDED.txn_type,
    amount = EXCLUDED.amount,
    currency_code = EXCLUDED.currency_code,
    txn_date = EXCLUDED.txn_date,
    remark = EXCLUDED.remark,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO biz.risk_score (
    tenant_id, entity_id, entity_type, score_value, score_band, score_date
)
SELECT 'tc',
       'EXP3C-RISK-' || lpad(gs::text, 4, '0'),
       CASE WHEN gs % 2 = 0 THEN 'CUSTOMER' ELSE 'ACCOUNT' END,
       (50 + gs)::numeric(10, 2),
       CASE WHEN gs % 3 = 0 THEN 'HIGH' WHEN gs % 3 = 1 THEN 'MEDIUM' ELSE 'LOW' END,
       CURRENT_DATE
FROM generate_series(1, 20) AS gs
ON CONFLICT (tenant_id, entity_id, score_date) DO UPDATE
SET entity_type = EXCLUDED.entity_type,
    score_value = EXCLUDED.score_value,
    score_band = EXCLUDED.score_band,
    updated_at = CURRENT_TIMESTAMP;
