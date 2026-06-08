SELECT COUNT(*)
FROM :"schema".worker_registry
WHERE status = 'DRAINING';
