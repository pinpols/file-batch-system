SELECT count(*) AS rows,
       sum(total_amount) AS amount,
       sum(event_count) AS events,
       max(high_water_mark) AS hwm
FROM biz.process_stage4_target
WHERE tenant_id = :'tenant_id'
  AND scenario = 'SHARDED'
  AND biz_date = :'biz_date'::date;
