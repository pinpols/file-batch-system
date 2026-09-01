SELECT
    (SELECT count(*) FROM biz.customer_account),
    (SELECT count(*) FROM biz.transaction),
    (SELECT count(*) FROM biz.risk_score);
