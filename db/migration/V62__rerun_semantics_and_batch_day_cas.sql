-- =========================================================
-- V61：重跑语义 + 批次日并发 + 时区快照
-- 针对五处设计灰色地带的数据层收口：
--   #1 job_instance 多义性：加 run_attempt，唯一键改为 (tenant_id, dedup_key, run_attempt)
--   #2 重跑语义：trigger_type 扩展 RERUN
--   #3 batch_day 状态机竞态：加 version 做 Spring Data JDBC @Version CAS
--   #4 biz_date 时区追溯：batch_day_instance 落 timezone_snapshot
-- =========================================================

-- #1 + #2 job_instance
ALTER TABLE batch.job_instance
    ADD COLUMN IF NOT EXISTS run_attempt INTEGER NOT NULL DEFAULT 1;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_job_instance_run_attempt'
    ) THEN
        ALTER TABLE batch.job_instance
            ADD CONSTRAINT ck_job_instance_run_attempt CHECK (run_attempt >= 1);
    END IF;
END $$;

ALTER TABLE batch.job_instance DROP CONSTRAINT IF EXISTS uk_job_instance_tenant_dedup;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_job_instance_tenant_dedup_attempt'
    ) THEN
        ALTER TABLE batch.job_instance
            ADD CONSTRAINT uk_job_instance_tenant_dedup_attempt UNIQUE (tenant_id, dedup_key, run_attempt);
    END IF;
END $$;

ALTER TABLE batch.job_instance DROP CONSTRAINT IF EXISTS ck_job_instance_trigger_type;
ALTER TABLE batch.job_instance ADD CONSTRAINT ck_job_instance_trigger_type
    CHECK (trigger_type IN ('SCHEDULED', 'API', 'MANUAL', 'EVENT', 'CATCH_UP', 'RERUN'));

-- #2 trigger_request
ALTER TABLE batch.trigger_request DROP CONSTRAINT IF EXISTS ck_trigger_request_type;
ALTER TABLE batch.trigger_request ADD CONSTRAINT ck_trigger_request_type
    CHECK (trigger_type IN ('API', 'MANUAL', 'EVENT', 'CATCH_UP', 'SCHEDULED', 'RERUN'));

-- #3 batch_day_instance 乐观锁
ALTER TABLE batch.batch_day_instance
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- #4 timezone snapshot
ALTER TABLE batch.batch_day_instance
    ADD COLUMN IF NOT EXISTS timezone_snapshot VARCHAR(64) NOT NULL DEFAULT 'UTC';

COMMENT ON COLUMN batch.job_instance.run_attempt IS
    '同一 dedup_key 下的重试编号；初始触发 = 1，RERUN 递增。与 dedup_key 组成业务唯一键，避免重跑撞车。';
COMMENT ON COLUMN batch.batch_day_instance.version IS
    'Spring Data JDBC @Version 乐观锁列；settle / reopen 并发时由框架抛 OptimisticLockingFailureException。';
COMMENT ON COLUMN batch.batch_day_instance.timezone_snapshot IS
    '创建批次日时从 business_calendar.timezone 抓取的快照，用于事后 cutoff_at/sla 回放，避免日历时区改动后历史数据解释不一致。';
