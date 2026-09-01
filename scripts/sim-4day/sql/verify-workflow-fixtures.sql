SELECT
    (SELECT count(*) FROM biz.customer_account WHERE customer_no = 'WF-CUST-000001'),
    (SELECT count(*) FROM biz.transaction WHERE txn_no = 'WF-TB-TXN-000001'),
    (SELECT count(*) FROM biz.risk_score WHERE entity_id = 'WF-ENT-000001');
