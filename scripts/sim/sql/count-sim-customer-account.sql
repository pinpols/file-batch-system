SELECT count(*)
FROM biz.customer_account
WHERE tenant_id = :'tenant_id'
  AND left(customer_no, length('CUST-' || :'batch_no' || '-')) = 'CUST-' || :'batch_no' || '-';
