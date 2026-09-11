\set ON_ERROR_STOP on
\pset tuples_only on
\pset format unaligned

select 'server_version_num|' || current_setting('server_version_num');
select 'max_connections|' || current_setting('max_connections');
select 'shared_buffers_bytes|' || pg_size_bytes(current_setting('shared_buffers'));
select 'work_mem_bytes|' || pg_size_bytes(current_setting('work_mem'));
select 'maintenance_work_mem_bytes|' || pg_size_bytes(current_setting('maintenance_work_mem'));
select 'wal_level|' || current_setting('wal_level');
select 'max_wal_size_bytes|' || pg_size_bytes(current_setting('max_wal_size'));
select 'checkpoint_timeout_seconds|' || extract(epoch from current_setting('checkpoint_timeout')::interval)::bigint;
select 'checkpoint_completion_target|' || current_setting('checkpoint_completion_target');
select 'wal_compression|' || current_setting('wal_compression');
select 'autovacuum|' || current_setting('autovacuum');
select 'track_io_timing|' || current_setting('track_io_timing');
select 'synchronous_commit|' || current_setting('synchronous_commit');
select 'data_checksums|' || current_setting('data_checksums');

select 'hot_table|' || expected.relname || '|' ||
       case when stats.relid is null then 'MISSING' else 'PRESENT' end || '|' ||
       coalesce(stats.n_live_tup, 0) || '|' ||
       coalesce(stats.n_dead_tup, 0) || '|' ||
       coalesce(extract(epoch from (clock_timestamp() - stats.last_autovacuum))::bigint, -1)
from (values
        ('trigger_request'),
        ('trigger_outbox_event'),
        ('job_instance'),
        ('job_partition'),
        ('job_task'),
        ('outbox_event')
     ) as expected(relname)
left join pg_stat_user_tables stats
  on stats.schemaname = 'batch'
 and stats.relname = expected.relname
order by expected.relname;
