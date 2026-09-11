SELECT instance_status, count(*) FROM batch.job_instance
WHERE created_at > now() - interval '10 minutes'
GROUP BY instance_status ORDER BY instance_status;
