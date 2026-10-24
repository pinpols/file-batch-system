WITH records AS (
    SELECT 'source' AS kind, tenant_id, account_id FROM biz.process_order_event
    UNION ALL
    SELECT 'summary', tenant_id, account_id FROM biz.process_account_summary
    UNION ALL
    SELECT 'copy', tenant_id, account_id FROM biz.process_event_copy
)
SELECT kind || '|'
       || count(*) FILTER (WHERE tenant_id = 'default-tenant' AND account_id LIKE :'owner_prefix' || '-ACCT-%') || '|'
       || count(*) FILTER (WHERE tenant_id = 'default-tenant' AND account_id LIKE :'other_prefix' || '-ACCT-%') || '|'
       || count(*) FILTER (WHERE tenant_id = 'default-tenant' AND account_id LIKE 'LTACCT-%') || '|'
       || count(*) FILTER (WHERE tenant_id = 'other-tenant')
FROM records GROUP BY kind
UNION ALL
SELECT 'other_fixture_rows|' || (
    (SELECT count(*) FROM biz.customer_account)
    + (SELECT count(*) FROM biz.settlement_detail)
    + (SELECT count(*) FROM biz.settlement_batch)
)
ORDER BY 1;
