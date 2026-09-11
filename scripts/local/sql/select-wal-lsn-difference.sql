SELECT pg_wal_lsn_diff(:'after_lsn', :'before_lsn')::bigint;
