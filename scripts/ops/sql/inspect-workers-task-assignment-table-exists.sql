SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = :'schema'
  AND table_name = 'task_assignment';
