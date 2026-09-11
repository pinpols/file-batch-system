SELECT count(*) || '|' || coalesce(max(customer_name), '') || '|' || coalesce(max(status), '')
FROM biz.customer_account
WHERE tenant_id = :'tenant_id'
  AND customer_no = 'S2CUPS000001';
