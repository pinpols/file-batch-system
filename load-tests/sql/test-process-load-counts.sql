SELECT count(*) || '|' || count(DISTINCT biz_date) || '|'
       || min(biz_date) || '|' || max(biz_date) || '|' || count(DISTINCT event_id)
FROM biz.process_order_event
WHERE tenant_id = 'default-tenant';
