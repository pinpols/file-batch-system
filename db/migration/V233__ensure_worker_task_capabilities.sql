-- 修复迁移历史曾占用 V223、但缺少 Worker 能力摘要列的数据库。
ALTER TABLE batch.worker_registry
    ADD COLUMN IF NOT EXISTS task_capabilities JSONB;

COMMENT ON COLUMN batch.worker_registry.task_capabilities IS
    'Worker 注册时上报的执行器能力摘要，仅用于运维展示，不作为任务路由依据';
