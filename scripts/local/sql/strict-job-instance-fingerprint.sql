SELECT coalesce(max(id), 0) || '/' || count(*) FROM batch.job_instance;
