-- =========================================================
-- cleanup-trigger-outbox-events.sql
-- 清理 trigger_outbox_event 表的历史事件,避免无限膨胀拖慢 TriggerOutboxRelay 扫描。
--
-- 配套:
--   ・V80 创建表 (ADR-010 trigger 异步解耦)
--   ・V139 加 archive 镜像 + archive_policy 种子
--
-- 保留策略（可通过 psql -v 覆盖）：
--   PUBLISHED 事件保留 :trigger_outbox_published_retention_days 天（默认 7 天）
--   GIVE_UP   事件保留 :trigger_outbox_giveup_retention_days   天（默认 30 天,事故复盘）
--   NEW / PUBLISHING / FAILED 永不主动删（活跃工作流）
--
-- 用法：
--   PGPASSWORD=... psql -h localhost -p 15432 -U batch_user -d batch_platform \
--     -v ON_ERROR_STOP=1 -f scripts/db/cleanup-trigger-outbox-events.sql
--
-- 自定义保留窗口：
--   psql ... -v trigger_outbox_published_retention_days=3 \
--           -v trigger_outbox_giveup_retention_days=14 -f ...
--
-- 推荐运维频率：每日一次（凌晨低峰期）。Cron 例：
--   5 3 * * *  PGPASSWORD=$PGPW psql -h $PGHOST -U $PGUSER -d batch_platform \
--               -v ON_ERROR_STOP=1 -f /opt/batch/scripts/db/cleanup-trigger-outbox-events.sql
--
-- ⚠️ 真删除,不可回滚（除非事务中断）。容量估算：
--   假设每分钟 1000 trigger fire → 每天 144 万 PUBLISHED → 7 天保留 ≈ 1000 万行。
--   超过该量级需把 retention 收紧到 3 天,或开启分区(参见 partition-migration/)。
-- =========================================================

\set ON_ERROR_STOP on

\if :{?trigger_outbox_published_retention_days}
\else
  \set trigger_outbox_published_retention_days 7
\endif
\if :{?trigger_outbox_giveup_retention_days}
\else
  \set trigger_outbox_giveup_retention_days 30
\endif

\echo '=== trigger_outbox_event 清理前统计 ==='
SELECT publish_status, count(*) AS rows,
       min(created_at) AS oldest, max(created_at) AS newest
FROM batch.trigger_outbox_event
GROUP BY publish_status
ORDER BY 1;

BEGIN;

-- 1) 先归档到冷表;ON CONFLICT 表示归档调度器已经搬过,本脚本可幂等补齐。
INSERT INTO archive.trigger_outbox_event_archive
SELECT *
FROM batch.trigger_outbox_event
WHERE (
        publish_status = 'PUBLISHED'
        AND created_at < now() - (:trigger_outbox_published_retention_days || ' days')::interval
      )
   OR (
        publish_status = 'GIVE_UP'
        AND created_at < now() - (:trigger_outbox_giveup_retention_days || ' days')::interval
      )
ON CONFLICT (id) DO NOTHING;

-- 2) PUBLISHED 已成功投递到 Kafka,仅保留近 N 天供回查
DELETE FROM batch.trigger_outbox_event
WHERE publish_status = 'PUBLISHED'
  AND created_at < now() - (:trigger_outbox_published_retention_days || ' days')::interval
  AND EXISTS (
      SELECT 1
      FROM archive.trigger_outbox_event_archive a
      WHERE a.id = batch.trigger_outbox_event.id
  );

-- 3) GIVE_UP 重试耗尽放弃的,多保留一段供事故复盘
DELETE FROM batch.trigger_outbox_event
WHERE publish_status = 'GIVE_UP'
  AND created_at < now() - (:trigger_outbox_giveup_retention_days || ' days')::interval
  AND EXISTS (
      SELECT 1
      FROM archive.trigger_outbox_event_archive a
      WHERE a.id = batch.trigger_outbox_event.id
  );

COMMIT;

\echo '=== trigger_outbox_event 清理后统计 ==='
SELECT publish_status, count(*) AS rows,
       min(created_at) AS oldest, max(created_at) AS newest
FROM batch.trigger_outbox_event
GROUP BY publish_status
ORDER BY 1;

\echo '=== 表/索引大小 ==='
SELECT pg_size_pretty(pg_total_relation_size('batch.trigger_outbox_event')) AS total_size;
