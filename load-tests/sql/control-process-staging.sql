SELECT 'process_staging_rows' AS metric, count(*)::text AS value
FROM batch.process_staging
WHERE tenant_id = :'tenant_id'
  AND batch_key LIKE '%' || :'run_id' || '%'
UNION ALL
SELECT 'process_staging_table_size', pg_size_pretty(pg_total_relation_size('batch.process_staging'));
