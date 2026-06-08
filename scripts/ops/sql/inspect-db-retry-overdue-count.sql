SELECT COUNT(*)
FROM :"schema".retry_schedule
WHERE retry_status = 'WAITING'
  AND next_retry_at < NOW() - INTERVAL '10 minutes';
