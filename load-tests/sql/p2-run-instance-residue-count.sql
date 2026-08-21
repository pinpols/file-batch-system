SELECT count(*) AS remaining_job_instances
FROM batch.job_instance
WHERE params_snapshot::text LIKE ('%' || :'run_id' || '%');
