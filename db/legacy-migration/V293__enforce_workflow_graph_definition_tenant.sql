-- 新库模型：节点、边和容量等待记录必须属于同租户 Workflow 定义。
-- 先建立并验证复合 FK，再移除旧单 ID FK；全部操作由同一 Flyway 事务提交。
-- 保持原 NO ACTION 删除语义，不删除业务行，不向草稿 JOB 编码添加无条件 FK。

ALTER TABLE batch.workflow_node
    ADD CONSTRAINT fk_workflow_node_definition_tenant
    FOREIGN KEY (tenant_id, workflow_definition_id)
    REFERENCES batch.workflow_definition (tenant_id, id) NOT VALID;

ALTER TABLE batch.workflow_node
    VALIDATE CONSTRAINT fk_workflow_node_definition_tenant;

ALTER TABLE batch.workflow_node
    DROP CONSTRAINT workflow_node_workflow_definition_id_fkey;

ALTER TABLE batch.workflow_edge
    ADD CONSTRAINT fk_workflow_edge_definition_tenant
    FOREIGN KEY (tenant_id, workflow_definition_id)
    REFERENCES batch.workflow_definition (tenant_id, id) NOT VALID;

ALTER TABLE batch.workflow_edge
    VALIDATE CONSTRAINT fk_workflow_edge_definition_tenant;

ALTER TABLE batch.workflow_edge
    DROP CONSTRAINT workflow_edge_workflow_definition_id_fkey;

CREATE INDEX idx_workflow_launch_wait_tenant_definition
    ON batch.workflow_launch_wait (tenant_id, workflow_definition_id);

ALTER TABLE batch.workflow_launch_wait
    ADD CONSTRAINT fk_workflow_launch_wait_definition_tenant
    FOREIGN KEY (tenant_id, workflow_definition_id)
    REFERENCES batch.workflow_definition (tenant_id, id) NOT VALID;

ALTER TABLE batch.workflow_launch_wait
    VALIDATE CONSTRAINT fk_workflow_launch_wait_definition_tenant;

ALTER TABLE batch.workflow_launch_wait
    DROP CONSTRAINT workflow_launch_wait_workflow_definition_id_fkey;
