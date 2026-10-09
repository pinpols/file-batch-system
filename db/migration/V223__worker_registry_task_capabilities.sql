-- 记录 Worker 注册时声明的执行器能力，供 Console 运维展示；调度仍使用 capability_tags。
ALTER TABLE batch.worker_registry
    ADD COLUMN task_capabilities JSONB;

COMMENT ON COLUMN batch.worker_registry.task_capabilities IS
    'Worker 注册时上报的执行器能力摘要，仅用于运维展示，不作为任务路由依据';
