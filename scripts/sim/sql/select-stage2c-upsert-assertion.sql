SELECT count(*) || '|' || coalesce(max(customer_name), '') || '|' || coalesce(max(status), '')
FROM biz.import_stage2c_customer
WHERE tenant_id = :'tenant_id'
  AND customer_no = 'S2CUPS000001';
