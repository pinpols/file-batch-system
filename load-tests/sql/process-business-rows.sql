SELECT 'source_rows' AS metric, count(*)::text AS value FROM biz.process_order_event
WHERE tenant_id = :'tenant_id' AND account_id LIKE :'run_id' || '-ACCT-%'
UNION ALL SELECT 'source_distinct_accounts', count(DISTINCT account_id)::text FROM biz.process_order_event
WHERE tenant_id = :'tenant_id' AND account_id LIKE :'run_id' || '-ACCT-%'
UNION ALL SELECT 'aggregate_target_rows', count(*)::text FROM biz.process_account_summary
WHERE tenant_id = :'tenant_id' AND account_id LIKE :'run_id' || '-ACCT-%'
UNION ALL SELECT 'copy_target_rows', count(*)::text FROM biz.process_event_copy
WHERE tenant_id = :'tenant_id' AND account_id LIKE :'run_id' || '-ACCT-%'
UNION ALL SELECT 'staging_live_rows', count(*)::text FROM batch.process_staging
WHERE tenant_id = :'tenant_id' AND batch_key LIKE '%' || :'run_id' || '%';
