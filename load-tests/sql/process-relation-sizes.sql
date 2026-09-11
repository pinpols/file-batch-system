SELECT relation, pg_size_pretty(pg_total_relation_size(relation::regclass)) AS total_size,
       pg_size_pretty(pg_relation_size(relation::regclass)) AS heap_size
FROM (VALUES ('biz.process_order_event'), ('batch.process_staging'),
             ('biz.process_account_summary'), ('biz.process_event_copy')) relations(relation);
