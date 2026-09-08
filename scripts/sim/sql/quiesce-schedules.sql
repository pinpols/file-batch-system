\echo '-- 1/2 CRON/FIXED_RATE → MANUAL(保留 expr / enabled;stage6c 等自我重置不受影响)'
WITH q AS (
  UPDATE batch.job_definition
     SET schedule_type = 'MANUAL'
   WHERE schedule_type IN ('CRON', 'FIXED_RATE')
  RETURNING 1)
SELECT count(*) AS quiesced_schedules FROM q;

\echo '-- 2/2 清空 dead_letter_task(sim 负向用例残留:SUCCESS 历史 + 确定性失败的重试 churn)'
WITH d AS (DELETE FROM batch.dead_letter_task RETURNING 1)
SELECT count(*) AS purged_dead_letters FROM d;

\echo '-- 残留核对:仍自动 fire 的定时 应为 0;死信 应为 0'
SELECT 'still_auto_fire' AS check, count(*) AS n
  FROM batch.job_definition WHERE schedule_type IN ('CRON', 'FIXED_RATE')
UNION ALL
SELECT 'remaining_dead_letters', count(*) FROM batch.dead_letter_task;
