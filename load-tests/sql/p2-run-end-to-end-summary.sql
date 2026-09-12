WITH scoped AS (
  SELECT
    tr.created_at AS requested_at,
    ji.created_at AS instance_created_at,
    ji.finished_at
  FROM batch.trigger_request tr
  JOIN batch.job_instance ji
    ON ji.trigger_request_id = tr.id
   AND ji.tenant_id = tr.tenant_id
  WHERE tr.request_id LIKE :'run_id' || '%'
), completed AS (
  SELECT *
  FROM scoped
  WHERE finished_at IS NOT NULL
)
SELECT
  count(*) AS completed,
  round(avg(extract(epoch FROM (instance_created_at - requested_at)))::numeric, 3)
    AS avg_launch_queue_seconds,
  round(percentile_cont(0.95) WITHIN GROUP (
    ORDER BY extract(epoch FROM (instance_created_at - requested_at)))::numeric, 3)
    AS p95_launch_queue_seconds,
  round(avg(extract(epoch FROM (finished_at - requested_at)))::numeric, 3)
    AS avg_end_to_end_seconds,
  round(percentile_cont(0.95) WITHIN GROUP (
    ORDER BY extract(epoch FROM (finished_at - requested_at)))::numeric, 3)
    AS p95_end_to_end_seconds,
  round(extract(epoch FROM (max(finished_at) - min(requested_at)))::numeric, 3)
    AS completion_window_seconds,
  round((count(*) / nullif(extract(epoch FROM (max(finished_at) - min(requested_at))), 0))::numeric, 3)
    AS effective_completed_per_second
FROM completed;
