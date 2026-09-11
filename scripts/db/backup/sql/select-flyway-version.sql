SELECT version
FROM batch.flyway_schema_history
WHERE success
ORDER BY installed_rank DESC
LIMIT 1;
