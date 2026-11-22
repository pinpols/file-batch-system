-- ============================================================
-- V82: job_step_instance 唯一约束加 tenant_id
-- ============================================================
-- 背景：
--   V13 创建 job_step_instance 时已含 tenant_id 列(NOT NULL),但唯一约束
--   uk_job_step_instance_task UNIQUE (job_task_id) 漏掉了 tenant_id。
--   V57 cross_tenant_check trigger 的设计前提是"所有业务表 unique 都含 tenant_id",
--   该约束破坏一致性,且 dirty delete / partition 误删等异常路径下,跨租户重复 job_task_id
--   不会被 PG 拦截。
--
-- 影响：
--   纯 schema 变更,不动 Java 代码 / mapper / entity。
--   现网数据 job_task_id 已经是全局唯一(BIGSERIAL),加 tenant_id 不会有冲突。
-- ============================================================

ALTER TABLE batch.job_step_instance
    DROP CONSTRAINT IF EXISTS uk_job_step_instance_task;

ALTER TABLE batch.job_step_instance
    ADD CONSTRAINT uk_job_step_instance_task UNIQUE (tenant_id, job_task_id);
