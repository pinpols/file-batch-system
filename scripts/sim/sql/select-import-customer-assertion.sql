SELECT count(*) || '|' || coalesce(max(customer_name), '')
FROM biz.customer_account
WHERE tenant_id = :'tenant_id'
  AND customer_no = :'customer_no';
