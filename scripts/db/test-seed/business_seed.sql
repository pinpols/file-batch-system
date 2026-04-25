-- =========================================================
-- batch_business.biz seed data (默认租户 / 联调初始数据)
--
-- ⚠️  目标库 = batch_business（不是 batch_platform）
--     在主库执行会污染 batch_platform.biz，业务代码读的是 batch_business。
--     正确路径：
--       1) scripts/data/load-system-test-data.sh（已 psql_business -d batch_business）
--       2) 手工：psql -d batch_business -f scripts/db/test-seed/business_seed.sql
--     E2E test 不读这两个 seed 文件（用 IMPORT_TEMPLATE_SEED 等独立 fixture）。
-- =========================================================

BEGIN;

TRUNCATE TABLE biz.settlement_detail, biz.settlement_batch, biz.customer_account RESTART IDENTITY CASCADE;

INSERT INTO biz.customer_account (
    id, tenant_id, customer_no, customer_name, customer_type, certificate_no, mobile_no, email,
    status, source_file_name, source_batch_no, source_trace_id, created_by, updated_by, created_at, updated_at
) VALUES
    (1001, 'default-tenant', 'CUST0001', 'Acme Retail Co., Ltd.', 'ENTERPRISE', '91310000000000001X', '13800000001', 'ops@acme.example', 'ACTIVE', 'customer-account-20260322.csv', 'IMP-20260322-001', 'trace-import-001', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (1002, 'default-tenant', 'CUST0002', 'Blue Ocean Tech Ltd.', 'ENTERPRISE', '91310000000000002X', '13800000002', 'finance@blueocean.example', 'ACTIVE', 'customer-account-20260322.csv', 'IMP-20260322-001', 'trace-import-001', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (1003, 'default-tenant', 'CUST0003', 'North Star Trading', 'ENTERPRISE', '91310000000000003X', '13800000003', 'contact@northstar.example', 'FROZEN', 'customer-account-20260322.csv', 'IMP-20260322-001', 'trace-import-001', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (1004, 'tenant-finance', 'CUST9001', 'Finance Holdings Ltd.', 'ENTERPRISE', '91310000000009001X', '13800009001', 'ops@finance.example', 'ACTIVE', 'customer-account-20260322.json', 'IMP-20260322-002', 'trace-import-002', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (1005, 'tenant-finance', 'CUST9002', 'Finance Market Services', 'PERSONAL', '91310000000009002X', '13800009002', 'services@finance.example', 'INACTIVE', 'customer-account-20260322.json', 'IMP-20260322-002', 'trace-import-002', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08');

INSERT INTO biz.settlement_batch (
    id, tenant_id, batch_no, biz_date, accounting_period, snapshot_mode, snapshot_ts, source_partitions,
    consistency_policy, batch_status, total_record_count, total_amount, currency, created_by, updated_by, created_at, updated_at
) VALUES
    (2001, 'default-tenant', 'SETTLE-20260322-A', DATE '2026-03-22', '2026-03', 'BIZ_DATE', TIMESTAMPTZ '2026-03-22 07:55:00+08', jsonb_build_array(1,2,3), 'EXPORT_SNAPSHOT', 'READY', 4, 8800.50, 'CNY', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (2002, 'default-tenant', 'SETTLE-20260322-B', DATE '2026-03-22', '2026-03', 'SNAPSHOT_TS', TIMESTAMPTZ '2026-03-22 08:10:00+08', jsonb_build_array(4,5), 'MATERIALIZED_STAGE', 'RUNNING', 3, 5200.00, 'CNY', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (2003, 'tenant-finance', 'SETTLE-20260322-C', DATE '2026-03-22', '2026-03', 'PERIOD', TIMESTAMPTZ '2026-03-22 07:50:00+08', jsonb_build_array(6,7), 'REPEATABLE_READ', 'EXPORTED', 5, 12345.67, 'CNY', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08');

INSERT INTO biz.settlement_detail (
    id, tenant_id, batch_id, settlement_no, customer_no, biz_date, accounting_period, order_no,
    gross_amount, fee_amount, net_amount, currency, settlement_status, exported_version, source_trace_id,
    created_by, updated_by, created_at, updated_at
) VALUES
    (3001, 'default-tenant', 2001, 'STL-20260322-0001', 'CUST0001', DATE '2026-03-22', '2026-03', 'ORD-0001', 3000.00, 30.00, 2970.00, 'CNY', 'READY', 0, 'trace-export-001', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:01:00+08', TIMESTAMPTZ '2026-03-22 08:01:00+08'),
    (3002, 'default-tenant', 2001, 'STL-20260322-0002', 'CUST0002', DATE '2026-03-22', '2026-03', 'ORD-0002', 1800.00, 18.00, 1782.00, 'CNY', 'READY', 0, 'trace-export-001', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:01:00+08', TIMESTAMPTZ '2026-03-22 08:01:00+08'),
    (3003, 'default-tenant', 2001, 'STL-20260322-0003', 'CUST0003', DATE '2026-03-22', '2026-03', 'ORD-0003', 2500.00, 25.00, 2475.00, 'CNY', 'READY', 0, 'trace-export-001', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:01:00+08', TIMESTAMPTZ '2026-03-22 08:01:00+08'),
    (3004, 'default-tenant', 2002, 'STL-20260322-0004', 'CUST0001', DATE '2026-03-22', '2026-03', 'ORD-0004', 1200.00, 12.00, 1188.00, 'CNY', 'EXPORTED', 1, 'trace-export-002', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:02:00+08', TIMESTAMPTZ '2026-03-22 08:02:00+08'),
    (3005, 'default-tenant', 2002, 'STL-20260322-0005', 'CUST0002', DATE '2026-03-22', '2026-03', 'ORD-0005', 1600.00, 16.00, 1584.00, 'CNY', 'EXPORTED', 1, 'trace-export-002', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:02:00+08', TIMESTAMPTZ '2026-03-22 08:02:00+08'),
    (3006, 'tenant-finance', 2003, 'STL-20260322-0006', 'CUST9001', DATE '2026-03-22', '2026-03', 'ORD-1001', 4000.00, 40.00, 3960.00, 'CNY', 'EXPORTED', 1, 'trace-export-003', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:03:00+08', TIMESTAMPTZ '2026-03-22 08:03:00+08'),
    (3007, 'tenant-finance', 2003, 'STL-20260322-0007', 'CUST9002', DATE '2026-03-22', '2026-03', 'ORD-1002', 2200.00, 22.00, 2178.00, 'CNY', 'EXPORTED', 1, 'trace-export-003', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:03:00+08', TIMESTAMPTZ '2026-03-22 08:03:00+08');

COMMIT;
