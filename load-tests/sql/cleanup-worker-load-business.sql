BEGIN;

DELETE FROM biz.customer_account
WHERE tenant_id = 'default-tenant' AND customer_no LIKE :'run_id' || '-IMP-%';
DELETE FROM biz.settlement_detail
WHERE tenant_id = 'default-tenant' AND settlement_no LIKE :'run_id' || '-SET-%';
DELETE FROM biz.settlement_batch
WHERE tenant_id = 'default-tenant' AND batch_no = :'run_id' || '-SETTLEMENT';
DELETE FROM biz.process_account_summary
WHERE tenant_id = 'default-tenant'
  AND (
    account_id LIKE 'LTACCT-%'
    OR account_id LIKE :'run_id' || '-ACCT-%'
    OR account_id LIKE left(regexp_replace(:'run_id', '[^A-Za-z0-9]', '', 'g'), 16) || '-ACCT-%'
  );
DELETE FROM biz.process_event_copy
WHERE tenant_id = 'default-tenant'
  AND account_id LIKE left(regexp_replace(:'run_id', '[^A-Za-z0-9]', '', 'g'), 16) || '-ACCT-%';
DELETE FROM biz.process_order_event
WHERE tenant_id = 'default-tenant'
  AND (
    event_id BETWEEN 9100000000 AND 9100004999
    OR account_id LIKE :'run_id' || '-ACCT-%'
    OR account_id LIKE left(regexp_replace(:'run_id', '[^A-Za-z0-9]', '', 'g'), 16) || '-ACCT-%'
  );

COMMIT;
