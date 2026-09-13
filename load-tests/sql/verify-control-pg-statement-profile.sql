SELECT current_setting('shared_preload_libraries'),
       current_setting('track_io_timing'),
       (SELECT count(*) FROM pg_extension WHERE extname = 'pg_stat_statements'),
       coalesce(current_setting('pg_stat_statements.track', true), '');
