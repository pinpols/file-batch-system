SELECT scenario, account_id, total_amount, event_count, high_water_mark
FROM biz.process_stage4_target
WHERE tenant_id = :'tenant_id'
ORDER BY scenario, account_id;
