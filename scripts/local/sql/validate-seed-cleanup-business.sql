DELETE FROM biz.settlement_batch WHERE batch_no LIKE :'pattern';
DELETE FROM biz.customer_account WHERE customer_no LIKE 'SEEDVAL_%';
