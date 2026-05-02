-- ============================================================
-- V84: workflow_node 加 tenant_id 列 + 改唯一约束
-- ============================================================
-- 背景：
--   V4 创建 workflow_node 时未含 tenant_id 列,uk_workflow_node_def_code
--   UNIQUE (workflow_definition_id, node_code) 通过 FK 间接到 tenant_id。
--   按"所有业务表默认携带 tenant_id + unique 约束含 tenant_id"原则,补 tenant_id 列,
--   并把唯一约束扩展为 (tenant_id, workflow_definition_id, node_code)。
--
-- 风险/兼容：
--   1. 加列 + backfill + SET NOT NULL 三步,原子性靠 Flyway 单 migration 事务保证
--   2. 现网数据 workflow_definition_id 全局唯一,backfill 不会冲突
--   3. 唯一约束加宽到三列后,原有数据保持唯一(因 (workflow_definition_id, node_code) 已唯一)
--   4. Java 侧同 PR 同步 entity / mapper.xml / upsert 路径 tenant_id 字段
-- ============================================================

ALTER TABLE batch.workflow_node ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);

UPDATE batch.workflow_node wn
   SET tenant_id = wd.tenant_id
  FROM batch.workflow_definition wd
 WHERE wn.workflow_definition_id = wd.id
   AND wn.tenant_id IS NULL;

ALTER TABLE batch.workflow_node ALTER COLUMN tenant_id SET NOT NULL;

ALTER TABLE batch.workflow_node DROP CONSTRAINT IF EXISTS uk_workflow_node_def_code;
ALTER TABLE batch.workflow_node
    ADD CONSTRAINT uk_workflow_node_def_code
    UNIQUE (tenant_id, workflow_definition_id, node_code);

-- 租户级节点检索辅助索引(console-api 列表查询常按 tenant_id + node_code 过滤)
CREATE INDEX IF NOT EXISTS idx_workflow_node_tenant_code
    ON batch.workflow_node (tenant_id, node_code);

COMMENT ON COLUMN batch.workflow_node.tenant_id IS 'V84(2026-05-03) 补充,取自 workflow_definition.tenant_id;落地 unique(tenant_id, ...) 多租隔离原则';
