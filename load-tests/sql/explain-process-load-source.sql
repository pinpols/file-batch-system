EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT tenant_id, account_id, biz_date, sum(amount) AS total_amount, max(event_id) AS high_water_mark
FROM biz.process_order_event
WHERE tenant_id = :'tenant_id'
  AND biz_date = :'biz_date'::date
  AND account_id LIKE :'account_prefix' || '%'
GROUP BY tenant_id, account_id, biz_date
LIMIT 100000;
