SELECT COUNT(*)
FROM :"schema".retry_schedule
WHERE retry_status = 'WAITING';
