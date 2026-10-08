SELECT count(*)
FROM public.local_pg_failover_drill
WHERE run_id = :'run_id'
  AND phase = :'phase';
