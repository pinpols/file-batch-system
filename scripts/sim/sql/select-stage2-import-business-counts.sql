SELECT tenant_id,
       count(*) FILTER (WHERE customer_no LIKE 'S2XML%') AS xml_rows,
       count(*) FILTER (WHERE customer_no LIKE 'S2FIX%') AS fixed_rows
FROM biz.customer_account
WHERE tenant_id = :'tenant_id'
GROUP BY tenant_id;
