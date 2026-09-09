SELECT count(*) || '|'
       || coalesce(sum(total_amount), 0) || '|'
       || coalesce(sum(event_count), 0) || '|'
       || coalesce(max(high_water_mark), 0)
FROM biz.process_stage4_target
WHERE tenant_id = :'tenant_id'
  AND scenario = 'JSONB'
  AND biz_date = :'biz_date'::date;
