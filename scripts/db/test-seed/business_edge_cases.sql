BEGIN;

INSERT INTO biz.settlement_batch (
    id, tenant_id, batch_no, biz_date, accounting_period, snapshot_mode, snapshot_ts, source_partitions,
    consistency_policy, batch_status, total_record_count, total_amount, currency, created_by, updated_by, created_at, updated_at
) VALUES
    (2004, 'default-tenant', 'SETTLE-20260322-D', DATE '2026-03-22', '2026-03', 'BIZ_DATE', TIMESTAMPTZ '2026-03-22 09:00:00+08', jsonb_build_array(8,9), 'EXPORT_SNAPSHOT', 'CANCELLED', 2, 2400.00, 'CNY', 'system', 'system', TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:00:00+08'),
    (2005, 'tenant-finance', 'SETTLE-20260322-E', DATE '2026-03-22', '2026-03', 'SNAPSHOT_TS', TIMESTAMPTZ '2026-03-22 09:05:00+08', jsonb_build_array(10,11), 'REPEATABLE_READ', 'ARCHIVED', 3, 6400.00, 'CNY', 'system', 'system', TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:00:00+08');

INSERT INTO biz.settlement_detail (
    id, tenant_id, batch_id, settlement_no, customer_no, biz_date, accounting_period, order_no,
    gross_amount, fee_amount, net_amount, currency, settlement_status, exported_version, source_trace_id,
    created_by, updated_by, created_at, updated_at
) VALUES
    (3008, 'default-tenant', 2004, 'STL-20260322-0008', 'CUST0001', DATE '2026-03-22', '2026-03', 'ORD-0008', 800.00, 8.00, 792.00, 'CNY', 'SETTLED', 0, 'trace-export-004', 'system', 'system', TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:00:00+08'),
    (3009, 'default-tenant', 2004, 'STL-20260322-0009', 'CUST0002', DATE '2026-03-22', '2026-03', 'ORD-0009', 600.00, 6.00, 594.00, 'CNY', 'FAILED', 0, 'trace-export-004', 'system', 'system', TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:00:00+08'),
    (3010, 'tenant-finance', 2005, 'STL-20260322-0010', 'CUST9001', DATE '2026-03-22', '2026-03', 'ORD-1010', 1000.00, 10.00, 990.00, 'CNY', 'REVERSED', 2, 'trace-export-005', 'system', 'system', TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:00:00+08');

COMMIT;
