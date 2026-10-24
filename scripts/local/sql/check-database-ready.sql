SELECT 1;

    WHEN to_regclass('biz.customer_account') IS NOT NULL
     AND to_regclass('biz.transaction') IS NOT NULL
     AND to_regclass('biz.risk_score') IS NOT NULL
     AND to_regclass('biz.settlement_batch') IS NOT NULL
     AND to_regclass('batch.process_staging') IS NOT NULL
    THEN 1
    ELSE 0
END;
