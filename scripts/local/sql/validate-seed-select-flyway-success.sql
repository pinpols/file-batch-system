SELECT success
FROM batch.flyway_schema_history
WHERE version = :'version';
