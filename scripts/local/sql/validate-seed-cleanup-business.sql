-- DANGER: 删除 validate-seed 业务夹具数据。仅限隔离本地测试库，并按 fixture pattern 精确筛选。
DELETE FROM biz.settlement_batch WHERE batch_no LIKE :'pattern';
DELETE FROM biz.customer_account WHERE customer_no LIKE 'SEEDVAL_%';
