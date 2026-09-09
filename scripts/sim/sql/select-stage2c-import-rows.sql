SELECT source_batch_no, customer_no, count(*) AS rows, max(customer_name) AS max_name
FROM biz.import_stage2c_customer
WHERE tenant_id = :'tenant_id'
  AND source_batch_no LIKE :'batch_no' || '%'
GROUP BY source_batch_no, customer_no
ORDER BY source_batch_no, customer_no;
