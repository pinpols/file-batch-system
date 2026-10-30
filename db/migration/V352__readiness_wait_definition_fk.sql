-- =========================================================
-- V352：readiness_wait 的同租户定义引用约束。
--
-- 背景：V351 新增 readiness_wait 时未加引用约束，与 ALIGN-DB-REF（V296 起）确立的
-- "新关系必须有同租户复合 FK"治理口径不一致。候选键 uk_workflow_definition_tenant_id
-- (tenant_id, id)（V247）现成可用，写法参照 V310 waiting_launch 的同租户复合 FK。
--
-- trigger_launch_recovery 刻意保持无 FK：该锚点存在的意义就是 prepare 从未提交、
-- request 行尚不存在，没有可引用对象；且 park() 在 Kafka error-handler recoverer
-- 线程内被调用，约束冲突向上抛会破坏消费端处置路径，故只保留应用层 tenantId/requestId
-- 非空校验。
-- =========================================================

ALTER TABLE batch.readiness_wait
    -- 新表(本迁移同批创建)无历史数据，无需 NOT VALID 的两阶段校验。
    -- squawk-ignore constraint-missing-not-valid
    ADD CONSTRAINT fk_readiness_wait_workflow_definition
    FOREIGN KEY (tenant_id, workflow_definition_id)
    REFERENCES batch.workflow_definition (tenant_id, id)
    ON DELETE NO ACTION ON UPDATE NO ACTION;

COMMENT ON CONSTRAINT fk_readiness_wait_workflow_definition ON batch.readiness_wait IS
    '同租户复合外键：等待必须指向真实定义（V247 候选键）；NO ACTION 提供父行删除保护。';
