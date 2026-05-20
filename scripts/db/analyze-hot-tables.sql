-- =========================================================
-- analyze-hot-tables.sql
-- 强制 ANALYZE 热表 / JSONB 列统计信息，让 PG planner 的 selectivity 估算保持稳定。
--
-- 依据: docs/analysis/dba-schema-review-2026-05-20.md §3.9 / Quick wins §8 / Top10 §3.9
--
-- 背景:
--   PG autovacuum 默认按表行数变化比例触发 ANALYZE,JSONB 列 + 大表(亿级)场景下
--   触发间隔过长,planner 用陈旧统计低估选择率,把 GIN / 复合索引退回 seq scan,
--   特别是 file_record.metadata_json / outbox_event.payload_json 上的容器键查询。
--
-- 用法:
--   PGPASSWORD=... psql -h localhost -p 15432 -U batch_user -d batch_platform \
--     -v ON_ERROR_STOP=1 -f scripts/db/analyze-hot-tables.sql
--
-- 推荐运维频率:
--   ・每日凌晨一次(配合 cleanup-*.sql 之后跑)
--   ・大批量数据导入 / archive 迁移后手动触发一次
--
-- Cron 例:
--   30 3 * * *  PGPASSWORD=$PGPW psql ... -f /opt/batch/scripts/db/analyze-hot-tables.sql
--
-- 副作用:
--   ・ANALYZE 不阻塞读写(只取 SHARE UPDATE EXCLUSIVE 短锁),可在业务时段执行
--   ・对超大表(>1 亿行)首次跑可能耗时 30s+,后续增量 < 10s
-- =========================================================

\set ON_ERROR_STOP on

\echo '=== ANALYZE 热表统计开始 ==='
\timing on

-- 调度 / 工作流 主线
ANALYZE batch.job_instance;
ANALYZE batch.job_partition;
ANALYZE batch.workflow_run;
ANALYZE batch.workflow_node_run;

-- 文件 / 派发 (含 JSONB metadata_json)
ANALYZE batch.file_record;
ANALYZE batch.file_dispatch_record;
ANALYZE batch.pipeline_instance;

-- Outbox / 事件
ANALYZE batch.outbox_event;
ANALYZE batch.event_delivery_log;
ANALYZE batch.trigger_outbox_event;

-- 审计 / 死信 / 日志
ANALYZE batch.console_operation_audit;
ANALYZE batch.dead_letter_task;
ANALYZE batch.job_execution_log;

\timing off

\echo '=== 各表 last_analyze 时间(校验是否真跑了) ==='
SELECT relname,
       to_char(last_analyze,      'YYYY-MM-DD HH24:MI:SS') AS last_analyze,
       to_char(last_autoanalyze,  'YYYY-MM-DD HH24:MI:SS') AS last_autoanalyze,
       n_live_tup
FROM pg_stat_user_tables
WHERE schemaname = 'batch'
  AND relname IN (
    'job_instance','job_partition','workflow_run','workflow_node_run',
    'file_record','file_dispatch_record','pipeline_instance',
    'outbox_event','event_delivery_log','trigger_outbox_event',
    'console_operation_audit','dead_letter_task','job_execution_log'
  )
ORDER BY relname;
