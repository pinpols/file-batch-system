-- =========================================================
-- cleanup-job-execution-log.sql
-- 清理 job_execution_log 执行日志表,避免无限累积压死 INSERT 写入与 console 查询。
--
-- 配套:
--   ・V6 创建表
--   ・V71 已建 archive.job_execution_log_archive
--   ・V141 加 archive_policy 种子(默认 30 天)
--
-- 保留策略（可通过 psql -v 覆盖）:
--   DEBUG / INFO 保留 :jel_info_retention_days 天（默认 7 天,体量大噪音多）
--   WARN / ERROR 保留 :jel_error_retention_days 天（默认 30 天,排障与告警依赖）
--   AUDIT       保留 :jel_audit_retention_days 天（默认 180 天,合规可调）
--
-- ⚠️ 真删除,容量估算: 单租户高峰 100k row/day → 30 天保留 ≈ 300 万行;
--    大规模租户(>10) 建议改用分区(参见 partition-migration/),否则索引重建会卡顿。
--
-- 用法：
--   PGPASSWORD=... psql -h localhost -p 15432 -U batch_user -d batch_platform \
--     -v ON_ERROR_STOP=1 -f scripts/db/cleanup-job-execution-log.sql
--
-- 推荐运维频率：每日一次。Cron 例：
--   10 3 * * *  PGPASSWORD=$PGPW psql ... -f /opt/batch/scripts/db/cleanup-job-execution-log.sql
-- =========================================================

\set ON_ERROR_STOP on

\if :{?jel_info_retention_days}
\else
  \set jel_info_retention_days 7
\endif
\if :{?jel_error_retention_days}
\else
  \set jel_error_retention_days 30
\endif
\if :{?jel_audit_retention_days}
\else
  \set jel_audit_retention_days 180
\endif

\echo '=== job_execution_log 清理前统计 ==='
SELECT log_level, log_type, count(*) AS rows,
       min(created_at) AS oldest, max(created_at) AS newest
FROM batch.job_execution_log
GROUP BY log_level, log_type
ORDER BY 1, 2;

BEGIN;

-- 1) 先归档到冷表;ON CONFLICT 表示归档调度器已经搬过,本脚本可幂等补齐。
INSERT INTO archive.job_execution_log_archive
SELECT *
FROM batch.job_execution_log
WHERE (
        log_level IN ('DEBUG', 'INFO')
        AND log_type <> 'AUDIT'
        AND created_at < now() - (:jel_info_retention_days || ' days')::interval
      )
   OR (
        log_level IN ('WARN', 'ERROR')
        AND log_type <> 'AUDIT'
        AND created_at < now() - (:jel_error_retention_days || ' days')::interval
      )
   OR (
        log_type = 'AUDIT'
        AND created_at < now() - (:jel_audit_retention_days || ' days')::interval
      )
ON CONFLICT (id) DO NOTHING;

-- 2) DEBUG / INFO 体量最大噪音最多,7 天后即可清
DELETE FROM batch.job_execution_log
WHERE log_level IN ('DEBUG', 'INFO')
  AND log_type <> 'AUDIT'
  AND created_at < now() - (:jel_info_retention_days || ' days')::interval
  AND EXISTS (
      SELECT 1
      FROM archive.job_execution_log_archive a
      WHERE a.id = batch.job_execution_log.id
  );

-- 3) WARN / ERROR 30 天,故障排查与告警链路依赖
DELETE FROM batch.job_execution_log
WHERE log_level IN ('WARN', 'ERROR')
  AND log_type <> 'AUDIT'
  AND created_at < now() - (:jel_error_retention_days || ' days')::interval
  AND EXISTS (
      SELECT 1
      FROM archive.job_execution_log_archive a
      WHERE a.id = batch.job_execution_log.id
  );

-- 4) AUDIT 类型独立保留窗口（合规优先）
DELETE FROM batch.job_execution_log
WHERE log_type = 'AUDIT'
  AND created_at < now() - (:jel_audit_retention_days || ' days')::interval
  AND EXISTS (
      SELECT 1
      FROM archive.job_execution_log_archive a
      WHERE a.id = batch.job_execution_log.id
  );

COMMIT;

\echo '=== job_execution_log 清理后统计 ==='
SELECT log_level, log_type, count(*) AS rows,
       min(created_at) AS oldest, max(created_at) AS newest
FROM batch.job_execution_log
GROUP BY log_level, log_type
ORDER BY 1, 2;

\echo '=== 表/索引大小 ==='
SELECT pg_size_pretty(pg_total_relation_size('batch.job_execution_log')) AS total_size;
