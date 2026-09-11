SELECT pg_get_constraintdef(oid)
FROM pg_constraint
WHERE conrelid = to_regclass(:'relation_name')
  AND conname = :'constraint_name';
