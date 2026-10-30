SELECT count(*)
FROM biz.import_stage2c_customer
WHERE tenant_id = :'tenant_id'
  AND source_batch_no = :'batch_no' || '-append'
  AND customer_no LIKE 'S2CAPP%';
