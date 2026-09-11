SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE datname IN (:'platform_database', :'business_database')
  AND pid <> pg_backend_pid();
