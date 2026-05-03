-- ============================================================
-- V88: job_task + outbox_event 加 priority 列, 让 dispatch 按 priority 排序
-- ============================================================
-- 背景: job_definition.priority 已存在(默认 5),但派发流程不传递 priority:
-- 1) job_task 创建时不拷 priority → 后续 selector / scheduler 看不到
-- 2) outbox_event 不带 priority → relay poll 纯按 id asc 出队 → 高优先级 task 排队
-- 3) WaitingPartitionDispatchScheduler 也是 FIFO 扫
--
-- 影响: 高优 job 提交后被低优大批量任务挤占 → 完成时间长尾, 优先级形同虚设
--
-- 修法:
-- - job_task 加 priority(default 5), 派发时从 job_instance.job_definition_id → job_definition.priority 拷贝
-- - outbox_event 加 priority, OutboxPollScheduler 按 priority desc, id asc 排序
-- - 加索引让 PG planner 走 idx 而非全表扫
--
-- 优先级语义: priority 数值越大越优先 (与 job_definition.priority 一致, 1-10 区间, 5 默认)
-- ============================================================

ALTER TABLE batch.job_task
    ADD COLUMN IF NOT EXISTS priority INTEGER NOT NULL DEFAULT 5;

ALTER TABLE batch.job_task
    ADD CONSTRAINT ck_job_task_priority CHECK (priority >= 0 AND priority <= 10);

ALTER TABLE batch.outbox_event
    ADD COLUMN IF NOT EXISTS priority INTEGER NOT NULL DEFAULT 5;

ALTER TABLE batch.outbox_event
    ADD CONSTRAINT ck_outbox_event_priority CHECK (priority >= 0 AND priority <= 10);

-- selector/scheduler 走 (tenant, status, priority desc) 索引
CREATE INDEX IF NOT EXISTS idx_job_task_priority
    ON batch.job_task (tenant_id, task_status, priority DESC, id ASC);

-- relay poll 走 (status, next_publish_at, priority desc) 索引
CREATE INDEX IF NOT EXISTS idx_outbox_event_priority_pending
    ON batch.outbox_event (publish_status, next_publish_at NULLS FIRST, priority DESC, id ASC)
    WHERE publish_status IN ('NEW', 'FAILED');

COMMENT ON COLUMN batch.job_task.priority IS 'V88 (2026-05-03): 拷自 job_definition.priority, 数值越大越优先 (默认 5, 范围 0-10)';
COMMENT ON COLUMN batch.outbox_event.priority IS 'V88 (2026-05-03): 拷自 source job_definition.priority, OutboxPollScheduler 按此 desc 排序优先派发高优 task';
