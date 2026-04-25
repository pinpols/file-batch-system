-- =========================================================
-- Quartz → 时间轮替换 Pre-flight 扫描脚本
--
-- 用途：阶段 1 实施前在 staging / 生产 batch_platform 库执行,
--       确认无阻塞性 cron 表达式 + 无 SLA 红色场景。
--
-- 运维使用:
--   psql -d batch_platform -f scripts/db/quartz-replacement-preflight-scan.sql
--
-- 关联文档：docs/architecture/quartz-replacement-design.md §16 Pre-flight Checklist 第 1 项
-- =========================================================

\echo '════════════════════════════════════════════════════════'
\echo '1. 全部 enabled=true 的 CRON trigger 一览'
\echo '════════════════════════════════════════════════════════'
SELECT id, tenant_id, job_code, schedule_expr, timezone
  FROM batch.job_definition
 WHERE enabled = true AND schedule_type = 'CRON'
 ORDER BY tenant_id, job_code;

\echo ''
\echo '════════════════════════════════════════════════════════'
\echo '2. 含 L / W / # 字符的 cron(Quartz 扩展字符)'
\echo '   方案沿用 Quartz CronExpression,这些字符仍支持,不阻塞;'
\echo '   仅作为审计记录,后续替换 cron 解析器时需要改写'
\echo '════════════════════════════════════════════════════════'
SELECT id, tenant_id, job_code, schedule_expr,
       CASE
         WHEN schedule_expr ~ 'L' THEN 'L (last)'
         WHEN schedule_expr ~ 'W' THEN 'W (weekday-nearest)'
         WHEN schedule_expr ~ '#' THEN '# (Nth weekday)'
       END AS extension_char
  FROM batch.job_definition
 WHERE enabled = true
   AND schedule_type = 'CRON'
   AND schedule_expr ~ '[LW#]'
 ORDER BY tenant_id, job_code;

\echo ''
\echo '════════════════════════════════════════════════════════'
\echo '3. 秒级高频 cron(秒位非常量) — SLA 红色场景'
\echo '   时间轮抖动 ±200ms+扫库延迟最坏 500ms,'
\echo '   < 60s 间隔的 cron 不适合,必须先迁出'
\echo '════════════════════════════════════════════════════════'
SELECT id, tenant_id, job_code, schedule_expr
  FROM batch.job_definition
 WHERE enabled = true
   AND schedule_type = 'CRON'
   AND (
     -- 秒位含 / 表示间隔触发(如 */5 = 每 5 秒)
     split_part(schedule_expr, ' ', 1) ~ '/'
     -- 秒位含 , 表示离散秒触发(如 0,15,30,45)
     OR split_part(schedule_expr, ' ', 1) ~ ','
   );

\echo ''
\echo '════════════════════════════════════════════════════════'
\echo '4. FIXED_RATE trigger(同样需要时间轮调度,但语义不同)'
\echo '════════════════════════════════════════════════════════'
SELECT id, tenant_id, job_code, schedule_expr, timezone
  FROM batch.job_definition
 WHERE enabled = true AND schedule_type = 'FIXED_RATE'
 ORDER BY tenant_id, job_code;

\echo ''
\echo '════════════════════════════════════════════════════════'
\echo '5. 按 schedule_type 统计'
\echo '════════════════════════════════════════════════════════'
SELECT schedule_type, count(*) AS total,
       count(*) FILTER (WHERE enabled = true) AS enabled_count
  FROM batch.job_definition
 GROUP BY schedule_type
 ORDER BY enabled_count DESC;

\echo ''
\echo '════════════════════════════════════════════════════════'
\echo '6. 当前 Quartz JobStore (QRTZ_TRIGGERS) 状态总数'
\echo '   切换前必须确认 QRTZ_TRIGGERS 数 = enabled cron 数'
\echo '   不一致说明 TriggerReconciler 有 drift'
\echo '════════════════════════════════════════════════════════'
SELECT
  (SELECT count(*) FROM batch.job_definition WHERE enabled = true AND schedule_type IN ('CRON', 'FIXED_RATE')) AS db_enabled_count,
  (SELECT count(*) FROM quartz.qrtz_triggers) AS quartz_trigger_count,
  (SELECT count(*) FROM quartz.qrtz_triggers WHERE next_fire_time > 0) AS quartz_active_count;

\echo ''
\echo '════════════════════════════════════════════════════════'
\echo '判定标准:'
\echo '  ✅ 第 3 项 0 行 → SLA 无阻塞,可进入实施'
\echo '  ✅ 第 6 项 db_enabled_count = quartz_active_count → reconciler 同步正常'
\echo '  🟡 第 2 项 > 0 → 信息性,不阻塞(沿用 Quartz cron 解析)'
\echo '════════════════════════════════════════════════════════'
