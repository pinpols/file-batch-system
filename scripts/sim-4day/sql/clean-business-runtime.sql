-- 业务库 biz.* 运行态数据清空(保留表结构 / 不动 config)。
-- ⚠️ 表名必须全部真实存在:整条 TRUNCATE 是单语句,任一表不存在 + psql ON_ERROR_STOP=1
--    会整条回滚 → biz 数据一行都清不掉,跨 run 累积(customer_account 曾涨到 2w+ 行拖慢 EXPORT)。
--    新增/删表请同步核对 information_schema,勿照搬别处清单。
TRUNCATE TABLE
  biz.customer_account, biz.transaction, biz.risk_score,
  biz.settlement_batch, biz.settlement_detail, biz.risk_alert,
  biz.process_order_event, biz.process_account_summary, biz.process_event_copy,
  biz.process_stage4_source, biz.process_stage4_target, biz.import_stage2c_customer,
  batch.process_staging
RESTART IDENTITY CASCADE;
