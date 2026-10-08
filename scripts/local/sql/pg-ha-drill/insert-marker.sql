INSERT INTO public.local_pg_failover_drill (run_id, phase)
VALUES (:'run_id', :'phase')
ON CONFLICT (run_id) DO UPDATE
SET phase = EXCLUDED.phase;
