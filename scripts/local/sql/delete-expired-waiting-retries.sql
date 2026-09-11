DELETE FROM batch.retry_schedule
WHERE retry_status = 'WAITING'
  AND next_retry_at < CURRENT_TIMESTAMP;
