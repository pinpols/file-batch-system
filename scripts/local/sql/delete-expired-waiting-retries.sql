-- DANGER: 删除已过期 WAITING retry 记录。核对保留窗口和目标数据库，避免丢失恢复线索。
DELETE FROM batch.retry_schedule
WHERE retry_status = 'WAITING'
  AND next_retry_at < CURRENT_TIMESTAMP;
