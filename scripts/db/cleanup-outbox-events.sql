-- =========================================================
-- cleanup-outbox-events.sql
-- 清理 outbox_event 表的历史事件，避免无限膨胀拖慢 OutboxPollScheduler 的 SELECT。
--
-- 保留策略（可通过 psql -v 覆盖）：
--   PUBLISHED 事件保留 :outbox_published_retention_days 天（默认 7 天）
--   GIVE_UP   事件保留 :outbox_giveup_retention_days   天（默认 30 天，便于事故复盘）
--   FAILED / PUBLISHING / NEW 事件**永不主动删**（这些是活跃工作流）
--
-- 用法：
--   PGPASSWORD=... psql -h localhost -p 15432 -U batch_user -d batch_platform \
--     -v ON_ERROR_STOP=1 -f scripts/db/cleanup-outbox-events.sql
--
-- 自定义保留窗口：
--   psql ... -v outbox_published_retention_days=3 -v outbox_giveup_retention_days=14 -f ...
--
-- 推荐运维频率：每日一次（凌晨低峰期）。Cron 例：
--   0 3 * * *  PGPASSWORD=$PGPW psql -h $PGHOST -U $PGUSER -d batch_platform \
--               -v ON_ERROR_STOP=1 -f /opt/batch/scripts/db/cleanup-outbox-events.sql
--
-- ⚠️ 真删除，不可回滚（除非事务中断）。容量估算：
--   假设每天 100 万 outbox_event 全部 PUBLISHED → 7 天保留 = 700 万行；
--   超过这个量级 PG 索引仍可控，但单 SELECT latency 会漂移，建议加大归档频率。
-- =========================================================

\set ON_ERROR_STOP on

-- 默认保留窗口（psql -v 可覆盖）
\if :{?outbox_published_retention_days}
\else
  \set outbox_published_retention_days 7
\endif
\if :{?outbox_giveup_retention_days}
\else
  \set outbox_giveup_retention_days 30
\endif

\echo '=== outbox_event 清理前统计 ==='
SELECT publish_status, count(*) AS rows,
       min(created_at) AS oldest, max(created_at) AS newest
FROM batch.outbox_event
GROUP BY publish_status
ORDER BY 1;

BEGIN;

-- 1) PUBLISHED 已成功投递，仅保留近 N 天用于回查
DELETE FROM batch.outbox_event
WHERE publish_status = 'PUBLISHED'
  AND created_at < now() - (:outbox_published_retention_days || ' days')::interval;

-- 2) GIVE_UP 重试耗尽放弃的，多保留一段（事故复盘用）
DELETE FROM batch.outbox_event
WHERE publish_status = 'GIVE_UP'
  AND created_at < now() - (:outbox_giveup_retention_days || ' days')::interval;

COMMIT;

\echo '=== outbox_event 清理后统计 ==='
SELECT publish_status, count(*) AS rows,
       min(created_at) AS oldest, max(created_at) AS newest
FROM batch.outbox_event
GROUP BY publish_status
ORDER BY 1;

\echo '=== 表/索引大小 ==='
SELECT pg_size_pretty(pg_total_relation_size('batch.outbox_event')) AS total_size;
