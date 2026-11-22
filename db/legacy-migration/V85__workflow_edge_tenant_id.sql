-- ============================================================
-- V85: workflow_edge 加 tenant_id 列 + 改唯一约束
-- ============================================================
-- 背景：与 V84 同源。V4 创建 workflow_edge 时未含 tenant_id 列;补齐落地"所有业务表默认携带
-- tenant_id + unique 约束含 tenant_id"原则。
-- ============================================================

ALTER TABLE batch.workflow_edge ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);

UPDATE batch.workflow_edge we
   SET tenant_id = wd.tenant_id
  FROM batch.workflow_definition wd
 WHERE we.workflow_definition_id = wd.id
   AND we.tenant_id IS NULL;

ALTER TABLE batch.workflow_edge ALTER COLUMN tenant_id SET NOT NULL;

ALTER TABLE batch.workflow_edge DROP CONSTRAINT IF EXISTS uk_workflow_edge;
ALTER TABLE batch.workflow_edge
    ADD CONSTRAINT uk_workflow_edge
    UNIQUE (tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type);

CREATE INDEX IF NOT EXISTS idx_workflow_edge_tenant_def
    ON batch.workflow_edge (tenant_id, workflow_definition_id);

COMMENT ON COLUMN batch.workflow_edge.tenant_id IS 'V85(2026-05-03) 补充,取自 workflow_definition.tenant_id;落地 unique(tenant_id, ...) 多租隔离原则';
