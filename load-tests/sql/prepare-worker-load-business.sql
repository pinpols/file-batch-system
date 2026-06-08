BEGIN;

SELECT setval(pg_get_serial_sequence('biz.settlement_batch', 'id'), COALESCE((SELECT max(id) FROM biz.settlement_batch), 1), true);
SELECT setval(pg_get_serial_sequence('biz.settlement_detail', 'id'), COALESCE((SELECT max(id) FROM biz.settlement_detail), 1), true);
SELECT setval(pg_get_serial_sequence('biz.customer_account', 'id'), COALESCE((SELECT max(id) FROM biz.customer_account), 1), true);

INSERT INTO biz.settlement_batch (
  tenant_id, batch_no, biz_date, accounting_period, snapshot_mode, snapshot_ts,
  consistency_policy, batch_status, total_record_count, total_amount, currency,
  created_by, updated_by, created_at, updated_at
) VALUES (
  'default-tenant', :'run_id' || '-SETTLEMENT', :'biz_date'::date, to_char(:'biz_date'::date, 'YYYY-MM'),
  'BATCH', now(), 'EXPORT_SNAPSHOT', 'READY', 5000, 500000.00, 'CNY',
  'load-test', 'load-test', now(), now()
) ON CONFLICT (tenant_id, batch_no) DO UPDATE SET
  biz_date = EXCLUDED.biz_date,
  batch_status = 'READY',
  total_record_count = EXCLUDED.total_record_count,
  total_amount = EXCLUDED.total_amount,
  updated_at = now();

WITH b AS (
  SELECT id FROM biz.settlement_batch
  WHERE tenant_id = 'default-tenant' AND batch_no = :'run_id' || '-SETTLEMENT'
)
INSERT INTO biz.settlement_detail (
  tenant_id, batch_id, settlement_no, customer_no, biz_date, accounting_period,
  order_no, gross_amount, fee_amount, net_amount, currency, settlement_status,
  exported_version, source_trace_id, created_by, updated_by, created_at, updated_at
)
SELECT
  'default-tenant',
  b.id,
  :'run_id' || '-SET-' || lpad(gs::text, 6, '0'),
  :'run_id' || '-CUST-' || lpad((gs % 1000)::text, 4, '0'),
  :'biz_date'::date,
  to_char(:'biz_date'::date, 'YYYY-MM'),
  :'run_id' || '-ORD-' || lpad(gs::text, 6, '0'),
  100.00,
  1.00,
  99.00,
  'CNY',
  'READY',
  0,
  :'run_id',
  'load-test',
  'load-test',
  now(),
  now()
FROM b, generate_series(1, 5000) gs
ON CONFLICT (tenant_id, settlement_no) DO UPDATE SET
  settlement_status = 'READY',
  updated_at = now();

CREATE TABLE IF NOT EXISTS biz.process_event_copy (
    tenant_id        VARCHAR(32)    NOT NULL,
    event_id         BIGINT         NOT NULL,
    account_id       VARCHAR(32)    NOT NULL,
    biz_date         DATE           NOT NULL,
    amount           NUMERIC(18, 2) NOT NULL,
    high_water_mark  BIGINT         NOT NULL,
    PRIMARY KEY (tenant_id, event_id)
);

DELETE FROM biz.process_event_copy
WHERE tenant_id = 'default-tenant'
  AND account_id LIKE left(regexp_replace(:'run_id', '[^A-Za-z0-9]', '', 'g'), 16) || '-ACCT-%';

DELETE FROM biz.process_account_summary
WHERE tenant_id = 'default-tenant'
  AND account_id LIKE left(regexp_replace(:'run_id', '[^A-Za-z0-9]', '', 'g'), 16) || '-ACCT-%';

DELETE FROM biz.process_order_event
WHERE tenant_id = 'default-tenant'
  AND account_id LIKE left(regexp_replace(:'run_id', '[^A-Za-z0-9]', '', 'g'), 16) || '-ACCT-%';

INSERT INTO biz.process_order_event (tenant_id, account_id, biz_date, event_id, amount)
SELECT
  'default-tenant',
  left(regexp_replace(:'run_id', '[^A-Za-z0-9]', '', 'g'), 16)
    || '-ACCT-'
    || lpad((gs % :process_account_count::bigint)::text, :process_account_width::integer, '0'),
  :'biz_date'::date,
  :process_event_id_start::bigint + gs,
  (gs % 100 + 1)::numeric
FROM generate_series(0, :process_source_rows::bigint - 1) gs;

COMMIT;
