-- =========================================================
-- cleanup-workflow-runs.sql
-- 归档 workflow_run + workflow_node_run。和 cleanup-success-instances.sql 互补：
--   后者清理 job_instance 主链路；本脚本专门清 workflow 子树（DAG 类型扇出大、累积快）。
--
-- 保留策略（可通过 psql -v 覆盖）：
--   终结态 workflow_run（SUCCESS / FAILED / TERMINATED）保留 :workflow_retention_days 天（默认 30）
--   仅清终结态：CREATED / RUNNING 永远跳过（活跃工作流）
--
-- 用法：
--   PGPASSWORD=... psql -h localhost -p 15432 -U batch_user -d batch_platform \
--     -v ON_ERROR_STOP=1 -f scripts/db/cleanup-workflow-runs.sql
--
-- 自定义保留窗口：
--   psql ... -v workflow_retention_days=14 -f ...
--
-- 推荐运维频率：每日一次（凌晨低峰期）。Cron 例：
--   0 4 * * *  PGPASSWORD=$PGPW psql -h $PGHOST -U $PGUSER -d batch_platform \
--               -v ON_ERROR_STOP=1 -f /opt/batch/scripts/db/cleanup-workflow-runs.sql
--
-- 自动化版本：本脚本对应 Java 调度器 WorkflowArchiveScheduler（每天 04:00），
-- 默认开启；本 SQL 仅作为人工救急 / 大批量补充处理使用。
--
-- ⚠️ 真删除，不可回滚（除非事务中断）。容量估算：单 workflow 平均 5 节点 → 每天 100 万 workflow run
-- → 30 天保留 = 3000 万 workflow_run + 1.5 亿 workflow_node_run。海量场景需 archive schema 双层。
-- =========================================================

\set ON_ERROR_STOP on

\if :{?workflow_retention_days}
\else
  \set workflow_retention_days 30
\endif

\echo '=== workflow_run 清理前统计（按状态） ==='
SELECT run_status, count(*) AS rows
FROM batch.workflow_run
GROUP BY run_status
ORDER BY rows DESC;

BEGIN;

-- 1) workflow_node_run（FK 到 workflow_run，必须先删）
WITH old_runs AS (
  SELECT id FROM batch.workflow_run
   WHERE run_status IN ('SUCCESS','FAILED','TERMINATED')
     AND finished_at IS NOT NULL
     AND finished_at < now() - (:workflow_retention_days || ' days')::interval
)
DELETE FROM batch.workflow_node_run
 WHERE workflow_run_id IN (SELECT id FROM old_runs);

-- 2) 根：workflow_run
DELETE FROM batch.workflow_run
 WHERE run_status IN ('SUCCESS','FAILED','TERMINATED')
   AND finished_at IS NOT NULL
   AND finished_at < now() - (:workflow_retention_days || ' days')::interval;

COMMIT;

\echo '=== workflow_run 清理后统计 ==='
SELECT run_status, count(*) AS rows
FROM batch.workflow_run
GROUP BY run_status
ORDER BY rows DESC;

\echo '=== 表大小 ==='
SELECT relname, pg_size_pretty(pg_total_relation_size('batch.' || relname)) AS total_size
FROM (VALUES ('workflow_run'), ('workflow_node_run')) t(relname);
