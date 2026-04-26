-- =========================================================
-- V72: workflow_node_run.output JSONB 列
--
-- ADR-009 Stage 1：上游节点产出捕获，供下游节点 DSL 引用
-- （参见 docs/architecture/adr/ADR-009-workflow-param-dsl.md）
--
-- 字段语义：
--   key   = 业务字段名（worker 决定，例如 fileId / recordCount / receiptCode）
--   value = 任意 JSON 类型（标量 / 对象 / 数组）
--
-- 写入时机：worker 上报 task SUCCESS 时；orchestrator 在
--   DefaultTaskOutcomeService.persistOutcome 写本字段。
--
-- 读取时机：DefaultSchedulePlanBuilder 派发下游 partition payload 前，
--   WorkflowParamResolver 解析 node_params 中的 $.nodes.<X>.output.<key> 引用。
--
-- 兼容性：
--   - 旧 worker 不上报 outputs → 字段保持 NULL → DSL 解析返回 null（保持现行为）
--   - 新增字段 nullable，无 default，平滑升级
-- =========================================================

-- 用 IF NOT EXISTS 让 migration 幂等：V71 的 archive 冷表是
-- `CREATE TABLE archive.X (LIKE batch.X)` 形态，如果 testcontainer / 复用 PG
-- 中 batch 表已含 output（再次重跑场景），克隆出的 archive 表也会自带 output
-- → 此时 ALTER ADD 会重复报错。幂等 ADD 既兼容首次也兼容已存在场景。
ALTER TABLE batch.workflow_node_run
  ADD COLUMN IF NOT EXISTS output JSONB;

COMMENT ON COLUMN batch.workflow_node_run.output IS
  'ADR-009: 节点 SUCCESS 时由 worker 上报的产出 Map，供下游节点 $.nodes.<X>.output.<key> 引用';

-- 同步给 archive 冷表加 output 列：ArchiveSchemaDriftCheck 启动自检要求
-- archive.* 与 batch.* schema 一致；不同步会让 orchestrator 启动失败。
ALTER TABLE archive.workflow_node_run_archive
  ADD COLUMN IF NOT EXISTS output JSONB;

COMMENT ON COLUMN archive.workflow_node_run_archive.output IS
  'ADR-009: 同 batch.workflow_node_run.output，归档时直接复制';

-- 暂不加索引：当前 workflow_node_run 量级（千级/天）下 JSON 引用查询走 FK 直连，
-- 不依赖 output 字段过滤。等量级上来再评估 GIN 索引。
