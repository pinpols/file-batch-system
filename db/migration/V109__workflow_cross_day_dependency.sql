-- =====================================================================
-- V109: workflow_node 跨批量日依赖（ADR-018）
-- =====================================================================
-- 背景:
--   当前 workflow_run 紧绑单 biz_date，节点只能引用同 run 内的上游
--   节点 output（ADR-009 DSL `$.nodes.<X>.output.<key>`）。月度汇总、
--   T+5 调账、回写式补数等场景需要"跨日依赖"，目前只能在 worker 内
--   SQL 拼 biz_date range，orchestrator 失去依赖图可视化与重放管控。
--
-- 决策（ADR-018 §决策 pipe 模型）:
--   workflow_run 仍单 biz_date；workflow_node 新增 cross_day_dependencies
--   JSONB 列声明跨日上游，runtime 由 CrossDayDependencyResolver 解析
--   到 result_version EFFECTIVE 行（ADR-017）。节点未齐则进入
--   WAITING_DEPENDENCY，由 reconciler 周期重试。
--
-- 字段:
--   cross_day_dependencies            JSONB array of:
--     {
--       "alias": "t_minus_1",        -- 引用别名（DSL $.crossDay.<alias>）
--       "jobCode": "DAILY_PNL",
--       "bizDateOffset": -1,          -- int days 或 enum offset tag
--       "bizDateRange": null,         -- 或 enum: PREV_5_BIZ_DAYS 等（与 offset 互斥）
--       "scope": "REQUIRED",          -- REQUIRED / OPTIONAL
--       "consumeVersionStrategy": "EFFECTIVE_ONLY",
--                                     -- EFFECTIVE_ONLY / LATEST_INCLUDING_PENDING / SPECIFIC_VERSION
--       "specificVersionNo": null     -- SPECIFIC_VERSION 时填
--     }
--
--   cross_day_dependency_timeout_seconds  INTEGER
--     依赖未齐的等待上限；超时后按 scope 决策（REQUIRED→FAIL, OPTIONAL→continue）。
--     默认 86400（24h），0 = 不超时（永久等）。
--
-- 兼容:
--   两个新列均允许 NULL/0；未声明跨日依赖的 workflow 行为不变。
-- =====================================================================

ALTER TABLE batch.workflow_node
    ADD COLUMN IF NOT EXISTS cross_day_dependencies JSONB,
    ADD COLUMN IF NOT EXISTS cross_day_dependency_timeout_seconds INTEGER NOT NULL DEFAULT 86400;

ALTER TABLE batch.workflow_node DROP CONSTRAINT IF EXISTS ck_workflow_node_cross_day_timeout;
ALTER TABLE batch.workflow_node ADD CONSTRAINT ck_workflow_node_cross_day_timeout
    CHECK (cross_day_dependency_timeout_seconds >= 0);

COMMENT ON COLUMN batch.workflow_node.cross_day_dependencies IS
    'ADR-018 跨批量日上游依赖声明; JSONB array of {alias, jobCode, bizDateOffset|bizDateRange, scope, consumeVersionStrategy, specificVersionNo?}; NULL = 无跨日依赖';
COMMENT ON COLUMN batch.workflow_node.cross_day_dependency_timeout_seconds IS
    'ADR-018 依赖未齐的等待上限秒数; 0 表示永不超时; 超时后按 scope: REQUIRED→FAIL, OPTIONAL→continue';

-- =====================================================================
-- workflow_node_run 状态机扩展：WAITING_DEPENDENCY
-- =====================================================================
-- 新增过渡状态：节点 READY 后发现跨日依赖未齐 → WAITING_DEPENDENCY，
-- 由 reconciler 监测；命中后回到 READY 等待 dispatcher。
-- =====================================================================

ALTER TABLE batch.workflow_node_run DROP CONSTRAINT IF EXISTS ck_workflow_node_run_status;
ALTER TABLE batch.workflow_node_run ADD CONSTRAINT ck_workflow_node_run_status
    CHECK (node_status IN (
        'READY', 'WAITING_DEPENDENCY', 'RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED'
    ));

-- =====================================================================
-- 等待依赖审计日志辅助：扩展现有 job_execution_log，无 schema 改动
-- =====================================================================
COMMENT ON TABLE batch.workflow_node IS
    'Workflow 节点定义；ADR-018 起新增 cross_day_dependencies / cross_day_dependency_timeout_seconds 支持跨批量日依赖';
