\getenv replication_user POSTGRES_REPLICATION_USER
\getenv replication_password POSTGRES_REPLICATION_PASSWORD

SELECT format(
  'CREATE ROLE %I WITH REPLICATION LOGIN PASSWORD %L',
  :'replication_user',
  :'replication_password'
)
WHERE NOT EXISTS (
  SELECT 1
  FROM pg_roles
  WHERE rolname = :'replication_user'
)
\gexec
