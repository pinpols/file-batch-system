SELECT version, description, installed_on
FROM flyway_schema_history
WHERE success = false
ORDER BY installed_rank DESC
LIMIT 5;
