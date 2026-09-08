SELECT job_code,
       count(*) AS total,
       count(*) FILTER (WHERE instance_status = 'SUCCESS') AS success,
       round(avg(extract(epoch FROM (finished_at - created_at)))
           FILTER (WHERE finished_at IS NOT NULL)::numeric, 3) AS avg_s,
       round(percentile_cont(0.50) WITHIN GROUP (ORDER BY extract(epoch FROM (finished_at - created_at)))
           FILTER (WHERE finished_at IS NOT NULL)::numeric, 3) AS p50_s,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM (finished_at - created_at)))
           FILTER (WHERE finished_at IS NOT NULL)::numeric, 3) AS p95_s,
       round(percentile_cont(0.99) WITHIN GROUP (ORDER BY extract(epoch FROM (finished_at - created_at)))
           FILTER (WHERE finished_at IS NOT NULL)::numeric, 3) AS p99_s
FROM batch.job_instance
WHERE params_snapshot::text LIKE '%' || :'run_id' || '%'
GROUP BY job_code
ORDER BY job_code;
