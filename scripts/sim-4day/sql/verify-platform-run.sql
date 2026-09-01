WITH run_instances AS (
    SELECT i.id, i.instance_status
    FROM batch.job_instance i
    JOIN batch.trigger_request r ON r.id = i.trigger_request_id
    WHERE r.request_id LIKE :'run_prefix'
), run_tasks AS (
    SELECT t.id, t.task_status
    FROM batch.job_task t
    JOIN run_instances i ON i.id = t.job_instance_id
), run_outbox AS (
    SELECT e.publish_status
    FROM batch.outbox_event e
    WHERE e.aggregate_type = 'JOB_TASK'
      AND e.aggregate_id IN (SELECT id FROM run_tasks)
)
SELECT
    (SELECT count(*) FROM run_instances),
    (SELECT count(*) FROM run_instances WHERE instance_status NOT IN ('SUCCESS')),
    (SELECT count(*) FROM run_instances WHERE instance_status = 'FAILED'),
    (SELECT count(*) FROM run_tasks),
    (SELECT count(*) FROM run_tasks WHERE task_status NOT IN ('SUCCESS')),
    (SELECT count(*) FROM run_tasks WHERE task_status = 'FAILED'),
    (SELECT count(*) FROM run_outbox WHERE publish_status <> 'PUBLISHED');
