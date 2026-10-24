-- 隔离空库的清理范围反例：同前缀运行、其它租户及旧固定编号均须保留。
\ir test-process-load-fixture.sql
CREATE TABLE biz.process_account_summary (tenant_id text, account_id text);
CREATE TABLE biz.process_event_copy (tenant_id text, account_id text);
CREATE TABLE biz.customer_account (tenant_id text, customer_no text);
CREATE TABLE biz.settlement_detail (tenant_id text, settlement_no text);
CREATE TABLE biz.settlement_batch (tenant_id text, batch_no text);

INSERT INTO biz.process_order_event VALUES
    ('default-tenant', :'owner_prefix' || '-ACCT-0001', '2026-10-18', 1, 1),
    ('default-tenant', :'other_prefix' || '-ACCT-0001', '2026-10-18', 2, 1),
    ('default-tenant', 'LTACCT-unrelated', '2026-10-18', 9100000000, 1),
    ('other-tenant', :'owner_prefix' || '-ACCT-0001', '2026-10-18', 3, 1);
INSERT INTO biz.process_account_summary
SELECT tenant_id, account_id FROM biz.process_order_event;
INSERT INTO biz.process_event_copy
SELECT tenant_id, account_id FROM biz.process_order_event;
INSERT INTO biz.customer_account VALUES
    ('default-tenant', :'run_id' || '-IMP-1'),
    ('default-tenant', :'other_run_id' || '-IMP-1');
INSERT INTO biz.settlement_detail VALUES
    ('default-tenant', :'run_id' || '-SET-1'),
    ('default-tenant', :'other_run_id' || '-SET-1');
INSERT INTO biz.settlement_batch VALUES
    ('default-tenant', :'run_id' || '-SETTLEMENT'),
    ('default-tenant', :'other_run_id' || '-SETTLEMENT');
