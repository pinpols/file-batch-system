SELECT pid
FROM pg_stat_activity
WHERE datname = current_database()
  AND usename = current_user
  AND state = 'active'
  AND query ILIKE '%process_order_event%'
  AND query ILIKE '%process_event_copy%'
  AND query NOT ILIKE '%pg_stat_activity%'
ORDER BY query_start NULLS LAST
LIMIT 1;
