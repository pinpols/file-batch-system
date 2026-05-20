-- =========================================================
-- cleanup-dead-letter-task.sql
-- 清理 dead_letter_task 死信表的历史记录,避免无限累积成事故黑洞。
--
-- 配套:
--   ・V7 创建表
--   ・V90 加 auto-retry 逻辑
--   ・V140 加 archive 镜像 + archive_policy 种子
--
-- 保留策略（可通过 psql -v 覆盖）：
--   SUCCESS 已成功重放,保留 :dlt_success_retention_days 天（默认 30 天）
--   GIVE_UP 已放弃,保留 :dlt_giveup_retention_days     天（默认 90 天,合规可调）
--   NEW / REPLAYING / FAILED 永不主动删（运维仍可能介入重放）
--
-- ⚠️ GIVE_UP 行可能涉及业务事故复盘,确认 retention 与合规要求(SOX / 行业留存)
--    一致后再开启自动 cron,默认建议手动触发或先 dry-run。
--
-- 用法：
--   PGPASSWORD=... psql -h localhost -p 15432 -U batch_user -d batch_platform \
--     -v ON_ERROR_STOP=1 -f scripts/db/cleanup-dead-letter-task.sql
--
-- 自定义保留窗口：
--   psql ... -v dlt_success_retention_days=14 -v dlt_giveup_retention_days=180 -f ...
--
-- 推荐运维频率：每周一次。Cron 例：
--   0 4 * * 0  PGPASSWORD=$PGPW psql ... -f /opt/batch/scripts/db/cleanup-dead-letter-task.sql
-- =========================================================

\set ON_ERROR_STOP on

\if :{?dlt_success_retention_days}
\else
  \set dlt_success_retention_days 30
\endif
\if :{?dlt_giveup_retention_days}
\else
  \set dlt_giveup_retention_days 90
\endif

\echo '=== dead_letter_task 清理前统计 ==='
SELECT replay_status, count(*) AS rows,
       min(created_at) AS oldest, max(created_at) AS newest
FROM batch.dead_letter_task
GROUP BY replay_status
ORDER BY 1;

-- ⚠️ V140 起 archive_policy 已为 dead_letter_task 种子 archive_enabled=TRUE。
-- 本脚本默认先 INSERT 到 archive.dead_letter_task_archive 再 DELETE,避免归档调度器尚未跑就
-- 物理删导致冷库缺数据。可通过 -v skip_archive=1 跳过(已确认调度器先跑过的情况)。
\if :{?skip_archive}
  \echo '!! skip_archive=1: 跳过归档前置,直接 DELETE。请确认归档调度器已处理目标范围 !!'
\else
  \echo '=== 步骤 1/2: 归档到 archive.dead_letter_task_archive ==='
  BEGIN;
  INSERT INTO archive.dead_letter_task_archive
  SELECT * FROM batch.dead_letter_task
  WHERE (replay_status = 'SUCCESS'
         AND created_at < now() - (:dlt_success_retention_days || ' days')::interval)
     OR (replay_status = 'GIVE_UP'
         AND created_at < now() - (:dlt_giveup_retention_days || ' days')::interval)
  ON CONFLICT (id) DO NOTHING;
  COMMIT;
\endif

\echo '=== 步骤 2/2: 物理删除热表数据 ==='
BEGIN;

-- 1) 先归档到冷表;ON CONFLICT 表示归档调度器已经搬过,本脚本可幂等补齐。
INSERT INTO archive.dead_letter_task_archive
SELECT *
FROM batch.dead_letter_task
WHERE (
        replay_status = 'SUCCESS'
        AND created_at < now() - (:dlt_success_retention_days || ' days')::interval
      )
   OR (
        replay_status = 'GIVE_UP'
        AND created_at < now() - (:dlt_giveup_retention_days || ' days')::interval
      )
ON CONFLICT (id) DO NOTHING;

-- 2) SUCCESS 已成功重放,审计价值有限
DELETE FROM batch.dead_letter_task
WHERE replay_status = 'SUCCESS'
  AND created_at < now() - (:dlt_success_retention_days || ' days')::interval
  AND EXISTS (
      SELECT 1
      FROM archive.dead_letter_task_archive a
      WHERE a.id = batch.dead_letter_task.id
  );

-- 3) GIVE_UP 重试耗尽,事故复盘保留窗口要按合规
DELETE FROM batch.dead_letter_task
WHERE replay_status = 'GIVE_UP'
  AND created_at < now() - (:dlt_giveup_retention_days || ' days')::interval
  AND EXISTS (
      SELECT 1
      FROM archive.dead_letter_task_archive a
      WHERE a.id = batch.dead_letter_task.id
  );

COMMIT;

\echo '=== dead_letter_task 清理后统计 ==='
SELECT replay_status, count(*) AS rows,
       min(created_at) AS oldest, max(created_at) AS newest
FROM batch.dead_letter_task
GROUP BY replay_status
ORDER BY 1;

\echo '=== 表/索引大小 ==='
SELECT pg_size_pretty(pg_total_relation_size('batch.dead_letter_task')) AS total_size;

\echo '=== 告警: NEW 状态 > 14 天的孤儿行 (需人工介入) ==='
SELECT count(*) AS orphan_new_rows
FROM batch.dead_letter_task
WHERE replay_status = 'NEW'
  AND created_at < now() - interval '14 days';
