-- 扩展 batch.step_registry.module 白名单加入 PROCESS。
-- 起因：batch-worker-process 启动时 ProcessStepBeanRegistrar 写 module=PROCESS 失败,被
-- ck_step_registry_module 拒绝(原约束只放 IMPORT/EXPORT/DISPATCH/ORCHESTRATOR)。
ALTER TABLE batch.step_registry DROP CONSTRAINT IF EXISTS ck_step_registry_module;
ALTER TABLE batch.step_registry
  ADD CONSTRAINT ck_step_registry_module CHECK (
    module IN ('IMPORT', 'EXPORT', 'DISPATCH', 'PROCESS', 'ORCHESTRATOR')
  );

COMMENT ON COLUMN batch.step_registry.module IS '所属模块：IMPORT / EXPORT / DISPATCH / PROCESS / ORCHESTRATOR';
