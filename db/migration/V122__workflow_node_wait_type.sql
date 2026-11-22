-- ADR-028 Sensor WAIT 节点 S4/S5：定义表允许 WAIT 节点。
--
-- V121 已放开 workflow_node_run.node_type 并增加 sensor runtime 字段；这里补齐
-- workflow_node.node_type 的定义侧约束，避免配置包 / seed / 控制台保存 WAIT 节点时被
-- ck_workflow_node_type 拦截。

ALTER TABLE batch.workflow_node
    DROP CONSTRAINT IF EXISTS ck_workflow_node_type;
ALTER TABLE batch.workflow_node
    ADD CONSTRAINT ck_workflow_node_type
    CHECK (node_type IN ('TASK', 'GATEWAY', 'FILE_STEP', 'START', 'END', 'WAIT', 'JOB'));
