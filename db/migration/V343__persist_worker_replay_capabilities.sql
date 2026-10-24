-- 持久化 Worker 注册时上报的执行器声明，供重放预览使用。
-- worker_registry 已受租户级 RLS 保护；此列沿用现有策略。

ALTER TABLE batch.worker_registry
    ADD COLUMN replay_capabilities JSONB;

COMMENT ON COLUMN batch.worker_registry.replay_capabilities IS
    'Worker 注册时上报的重放能力声明目录；未声明时按失败关闭处理。';
