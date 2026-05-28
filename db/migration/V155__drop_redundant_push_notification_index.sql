-- V155: 删 console_push_job_notification 的冗余单列索引
--
-- V152 建表时附带 idx_console_push_job_notification_notified_at(notified_at) 单列索引,
-- 同表 UNIQUE (tenant_id, job_instance_id) 已隐式 BTREE 索引,push poller 走 UNIQUE 路径,
-- 单列 notified_at 索引在生产查询里没有用处但有写放大代价。
-- 与 V143 hot_index_consolidation 的"删冗余索引"方向一致。
-- 若 monitoring / debug 真需要时序查询,届时再补 partial index 而非全表。

DROP INDEX IF EXISTS batch.idx_console_push_job_notification_notified_at;
