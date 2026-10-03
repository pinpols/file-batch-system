SELECT COALESCE(
    max(version::integer) FILTER (WHERE success AND version ~ '^[0-9]+$'),
    0
)
FROM batch.flyway_schema_history;
