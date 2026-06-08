SELECT pipeline_type, run_status, count(*)
FROM batch.pipeline_instance
GROUP BY pipeline_type, run_status
ORDER BY 1, 2;
