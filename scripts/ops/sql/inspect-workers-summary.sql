SELECT status, COUNT(*) AS cnt
FROM :"schema".worker_registry
GROUP BY status
ORDER BY status;
