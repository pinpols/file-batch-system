SELECT 'ta.customer_account|' || count(*)
FROM biz.customer_account
WHERE tenant_id = 'ta'
  AND customer_no LIKE 'C' || :'token' || '%'
UNION ALL
SELECT 'tb.transaction|' || count(*)
FROM biz.transaction
WHERE tenant_id = 'tb'
  AND txn_date = :'biz_date'::date
  AND remark LIKE 'sim-mainline-' || :'batch_no' || '-%'
UNION ALL
SELECT 'tc.risk_score|' || count(*)
FROM biz.risk_score
WHERE tenant_id = 'tc'
  AND score_date = :'biz_date'::date
  AND entity_id LIKE 'E' || :'token' || '%';
