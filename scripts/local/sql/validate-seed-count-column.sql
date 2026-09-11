SELECT count(*)
FROM information_schema.columns
WHERE table_schema = :'table_schema'
  AND table_name = :'table_name'
  AND column_name = :'column_name';
