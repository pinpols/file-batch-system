SELECT current_setting('shared_preload_libraries'),
       current_setting('track_io_timing'),
       count(*)
FROM pg_extension
WHERE extname = 'pg_stat_statements'
GROUP BY current_setting('shared_preload_libraries'), current_setting('track_io_timing');
