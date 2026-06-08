SELECT version
FROM batch.flyway_schema_history
WHERE success = true
ORDER BY installed_rank;
