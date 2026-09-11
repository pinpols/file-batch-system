SELECT count(*)
FROM batch.job_instance
WHERE created_at <= :'target_time'::timestamptz;
