SELECT schemaname, relname, n_live_tup, n_dead_tup, seq_scan, seq_tup_read,
       idx_scan, n_tup_ins, n_tup_upd, n_tup_del, vacuum_count, autovacuum_count
FROM pg_stat_user_tables
WHERE (schemaname, relname) IN (('biz','process_order_event'), ('batch','process_staging'),
                                ('biz','process_account_summary'), ('biz','process_event_copy'))
ORDER BY schemaname, relname;
