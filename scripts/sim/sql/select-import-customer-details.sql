SELECT tenant_id,
       customer_no,
       count(*) AS rows,
       max(customer_name) AS customer_name,
       max(source_batch_no) AS source_batch_no
FROM biz.customer_account
WHERE tenant_id = :'tenant_id'
  AND customer_no = :'customer_no'
GROUP BY tenant_id, customer_no;
