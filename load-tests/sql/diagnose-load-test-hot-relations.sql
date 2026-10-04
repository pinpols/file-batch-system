SELECT
    schemaname,
    relname,
    pg_size_pretty(pg_total_relation_size((quote_ident(schemaname) || chr(46) || quote_ident(relname))::regclass)) AS total_size
FROM pg_stat_user_tables
ORDER BY pg_total_relation_size((quote_ident(schemaname) || chr(46) || quote_ident(relname))::regclass) DESC
LIMIT 12;
