SELECT count(*) || '|' || count(*) FILTER (WHERE customer_no = 'S2CREPSTALE')
FROM biz.import_stage2c_customer
WHERE tenant_id = :'tenant_id'
  AND source_batch_no = :'batch_no' || '-replace';
