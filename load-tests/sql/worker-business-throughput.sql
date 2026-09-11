SELECT 'import_loaded_rows' AS metric, count(*)::text AS value FROM biz.customer_account
WHERE tenant_id = :'tenant_id' AND customer_name LIKE 'Load Test Customer ' || :'run_id' || ' %'
UNION ALL SELECT 'export_source_rows', count(*)::text FROM biz.settlement_detail
WHERE tenant_id = :'tenant_id' AND settlement_no LIKE :'run_id' || '-SET-%'
UNION ALL SELECT 'process_source_rows', count(*)::text FROM biz.process_order_event
WHERE tenant_id = :'tenant_id' AND account_id LIKE :'account_prefix' || '-ACCT-%'
UNION ALL SELECT 'process_target_rows', count(*)::text FROM biz.process_account_summary
WHERE tenant_id = :'tenant_id' AND account_id LIKE :'account_prefix' || '-ACCT-%';
