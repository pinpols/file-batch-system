SELECT count(*) FROM biz.customer_account
WHERE tenant_id = :'tenant_id' AND customer_no LIKE 'BNDL%';
