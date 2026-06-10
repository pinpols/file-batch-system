TRUNCATE TABLE
  biz.customer_account, biz.customer_processed, biz.transaction, biz.risk_score,
  biz.settlement_batch, biz.settlement_detail, biz.risk_alert,
  biz.process_order_event, biz.process_account_summary, biz.process_event_copy,
  batch.process_staging
RESTART IDENTITY CASCADE;
