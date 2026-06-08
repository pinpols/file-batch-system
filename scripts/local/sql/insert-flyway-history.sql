INSERT INTO batch.flyway_schema_history (
  installed_rank, version, description, type, script, checksum, installed_by, execution_time, success
)
VALUES (
  (SELECT COALESCE(MAX(installed_rank), 0) + 1 FROM batch.flyway_schema_history),
  :'version',
  :'description',
  'SQL',
  :'script',
  0,
  'batch_user',
  0,
  true
);
