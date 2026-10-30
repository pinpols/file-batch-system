-- DANGER: 删除压测业务数据。仅限专用本地压测库；先核对业务库连接和 RUN_ID/业务日期筛选条件。
BEGIN;

DELETE FROM biz.customer_account
WHERE tenant_id = 'default-tenant' AND customer_no LIKE :'run_id' || '-IMP-%'
  AND regexp_replace(customer_no, '-IMP-[0-9]+$', '') = :'run_id';
DELETE FROM biz.settlement_detail
WHERE tenant_id = 'default-tenant' AND settlement_no LIKE :'run_id' || '-SET-%'
  AND regexp_replace(settlement_no, '-SET-[0-9]+$', '') = :'run_id';
DELETE FROM biz.settlement_batch
WHERE tenant_id = 'default-tenant' AND batch_no = :'run_id' || '-SETTLEMENT';
DELETE FROM biz.process_account_summary
WHERE tenant_id = 'default-tenant'
  AND account_id LIKE left(encode(sha256(convert_to(:'run_id', 'UTF8')), 'hex'), 16) || '-ACCT-%';
DELETE FROM biz.process_event_copy
WHERE tenant_id = 'default-tenant'
  AND account_id LIKE left(encode(sha256(convert_to(:'run_id', 'UTF8')), 'hex'), 16) || '-ACCT-%';
DELETE FROM biz.process_order_event
WHERE tenant_id = 'default-tenant'
  AND account_id LIKE left(encode(sha256(convert_to(:'run_id', 'UTF8')), 'hex'), 16) || '-ACCT-%';

COMMIT;
-- DANGER: 删除压测业务数据。仅限专用本地压测库；先核对业务库连接和 RUN_ID/业务日期筛选条件。
