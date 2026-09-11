SELECT count(*)
FROM batch.trigger_request
WHERE request_id LIKE :'pattern'
   OR (:'include_job_code'::boolean AND job_code LIKE :'pattern');
