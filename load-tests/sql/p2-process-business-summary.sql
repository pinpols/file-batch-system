SELECT
  'process_event_copy_rows' AS metric,
  count(*)::text AS value
FROM biz.process_event_copy
WHERE tenant_id = :'tenant_id'
  AND account_id LIKE left(regexp_replace(:'run_id', '[^A-Za-z0-9]', '', 'g'), 16) || '-ACCT-%'
UNION ALL
SELECT
  'process_staging_rows',
  count(*)::text
FROM batch.process_staging
WHERE tenant_id = :'tenant_id'
  AND batch_key LIKE '%' || :'run_id' || '%'
UNION ALL
SELECT
  'source_rows',
  count(*)::text
FROM biz.process_order_event
WHERE tenant_id = :'tenant_id'
  AND account_id LIKE left(regexp_replace(:'run_id', '[^A-Za-z0-9]', '', 'g'), 16) || '-ACCT-%';
