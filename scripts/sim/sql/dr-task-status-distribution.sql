SELECT task_status, count(*)
FROM batch.job_task
GROUP BY task_status
ORDER BY count(*) DESC;
