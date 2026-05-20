-- =========================================================
-- V143: 热表索引补强 — partial active + biz_date 维度
--
-- 依据: docs/analysis/dba-schema-review-2026-05-20.md §5 / Quick wins §8 / Top10 §3.2
--
-- 本迁移**只新增,不 DROP**:
--   DBA 报告里建议合并 V8 + V133 上的重叠索引,但合并前提是有
--   `pg_stat_user_indexes.idx_scan` 数据证明老索引零调用。本迁移先把"明显缺
--   的"补齐,DROP 候选移到 docs/runbook/index-consolidation-2026-05.md, 走
--   "取证 → 灰度 → DROP" 三阶段, 严禁单 PR 同时 ADD + DROP, 否则 rollback 后无法恢复。
--
-- 新增索引列表:
--   1) job_instance 活跃实例 partial — scheduler / wait 队列扫描
--   2) job_instance (tenant_id, biz_date DESC, instance_status) — cleanup-success-instances.sql
--      的核心过滤路径,V8 的 (tenant_id, biz_date, instance_status) 缺 DESC, 对 ORDER BY 失效
--   3) file_record 未删除 partial — DataGovernance / ArrivalGroup 列表过滤
--
-- 影响:
--   ・空间: 三条索引按当前生产规模 < 500MB, 增量;
--   ・写入: 每条 INSERT/UPDATE 增加索引维护, partial 索引仅活跃行有开销, 影响极小;
--   ・无锁影响: CREATE INDEX IF NOT EXISTS 默认会拿 SHARE 锁阻塞写, 大表建议
--     维护窗口执行;或临时手动 psql 改为 CONCURRENTLY(Flyway 默认事务包不允许)。
-- =========================================================

-- 1) job_instance 活跃实例 partial — scheduler hot path
--    (scheduler / WaitingPromoteScheduler / cleanup-orphan 都按 active status 过滤)
CREATE INDEX IF NOT EXISTS idx_job_instance_active_tenant_created
    ON batch.job_instance (tenant_id, created_at)
    WHERE instance_status IN ('CREATED', 'WAITING', 'READY', 'RUNNING');

-- 2) job_instance 按业务日期降序 — cleanup-success-instances.sql / Console biz_date 筛选
--    与 V8 的 idx_job_instance_biz_date 不同:本索引带 DESC 与 ORDER BY biz_date DESC 对齐,
--    Postgres 走 backward scan 时 V8 索引同样可用,故并非完全冗余, 留作 DROP 候选。
CREATE INDEX IF NOT EXISTS idx_job_instance_tenant_bizdate_status
    ON batch.job_instance (tenant_id, biz_date DESC, instance_status);

-- 3) file_record 未删除 partial — DataGovernance / ArrivalGroup 列表
CREATE INDEX IF NOT EXISTS idx_file_record_active_tenant_bizdate
    ON batch.file_record (tenant_id, biz_date)
    WHERE file_status NOT IN ('DELETED', 'ARCHIVED');

COMMENT ON INDEX batch.idx_job_instance_active_tenant_created IS
    'DBA-2026-05-20 P1-1 — 活跃实例 partial, scheduler/wait 队列扫描专用';
COMMENT ON INDEX batch.idx_job_instance_tenant_bizdate_status IS
    'DBA-2026-05-20 P0-2 / P1-1 — cleanup-success-instances.sql 与 console biz_date 列表 ORDER BY DESC';
COMMENT ON INDEX batch.idx_file_record_active_tenant_bizdate IS
    'DBA-2026-05-20 P1-1 — 未删除文件 partial, DataGovernance 列表过滤';
