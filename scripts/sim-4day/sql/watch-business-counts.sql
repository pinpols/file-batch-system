SELECT 'customer_account', count(*) FROM biz.customer_account
UNION ALL
SELECT 'transaction', count(*) FROM biz.transaction
UNION ALL
SELECT 'risk_score', count(*) FROM biz.risk_score;
