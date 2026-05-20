-- =============================================================================
-- V100: 性能索引补强 — JSONB GIN + tenant_id 复合索引
--
-- 背景: SQL 审计发现 file_record.metadata_json 的 jsonb_exists 反复全表扫(数百万行)
--      + 部分高频 WHERE (tenant_id + status / tenant_id + biz_date) 缺复合索引,Console
--      list 查询命中率低。
--
-- 影响表 (生产数据量参考):
--   batch.file_record       — 数百万 (FileArrivalGroupMapper.xml jsonb_exists)
--   batch.job_instance      — 千万级 (idx_job_instance_failure_class 已存,但 tenant_id+
--                                       status 复合索引缺;status filter 高频)
--   batch.workflow_run      — 百万级 (tenant_id+status+biz_date 高频组合)
--
-- 注:生产应用需在低峰期 + 临时切到 CONCURRENTLY(本仓库 Flyway 默认事务包裹,与
-- CONCURRENTLY 冲突,故 SQL 不带);灰度过程中可走 manual psql 直连方式创建。
-- =============================================================================

-- 1) file_record.metadata_json GIN 索引,加速 jsonb_exists / @> / ? 操作
--    覆盖 FileArrivalGroupMapper.selectByQuery / countByQuery / FileGovernanceMapper.selectArrivalGroupSummaries
CREATE INDEX IF NOT EXISTS idx_file_record_metadata_json_gin
    ON batch.file_record USING GIN (metadata_json);

-- 2) job_instance (tenant_id, instance_status, started_at DESC) 复合索引,加速 Console list
--    覆盖 JobInstanceMapper.selectByQuery (status filter + ORDER BY started_at DESC)
CREATE INDEX IF NOT EXISTS idx_job_instance_tenant_status_started
    ON batch.job_instance (tenant_id, instance_status, started_at DESC);

-- 3) workflow_run (tenant_id, run_status, biz_date DESC) 复合索引,加速 Console dashboard / list
CREATE INDEX IF NOT EXISTS idx_workflow_run_tenant_status_bizdate
    ON batch.workflow_run (tenant_id, run_status, biz_date DESC);

-- 4) outbox_event (tenant_id, publish_status, next_publish_at) — orchestrator advance 查询热路径
--    与 V6 的 idx_outbox_event_pending 互补:V6 是 (publish_status, next_publish_at) 全租户视角,
--    本索引为单租户查询(Console outbox 观察台)优化。
CREATE INDEX IF NOT EXISTS idx_outbox_event_tenant_status_scheduled
    ON batch.outbox_event (tenant_id, publish_status, next_publish_at);
