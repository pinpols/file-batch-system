WITH
ji AS (
    SELECT * FROM batch.job_instance WHERE tenant_id = :'tenant_id'
),
jp AS (
    SELECT partition.*
    FROM batch.job_partition partition
    JOIN batch.job_instance instance
      ON instance.tenant_id = partition.tenant_id
     AND instance.id = partition.job_instance_id
    WHERE partition.tenant_id = :'tenant_id'
),
jt AS (
    SELECT task.*
    FROM batch.job_task task
    JOIN batch.job_instance instance
      ON instance.tenant_id = task.tenant_id
     AND instance.id = task.job_instance_id
    WHERE task.tenant_id = :'tenant_id'
),
oe AS (
    SELECT * FROM batch.outbox_event WHERE tenant_id = :'tenant_id'
),
tr AS (
    SELECT * FROM batch.trigger_request WHERE tenant_id = :'tenant_id'
),
fd AS (
    SELECT * FROM batch.file_dispatch_record WHERE tenant_id = :'tenant_id'
),
wr AS (
    SELECT * FROM batch.worker_registry WHERE tenant_id = :'tenant_id'
)
SELECT concat_ws(',',
    to_char(clock_timestamp(), 'YYYY-MM-DD"T"HH24:MI:SS.MS'),
    (SELECT count(*) FROM ji WHERE instance_status = 'CREATED'),
    (SELECT count(*) FROM ji WHERE instance_status = 'WAITING'),
    (SELECT count(*) FROM ji WHERE instance_status = 'READY'),
    (SELECT count(*) FROM ji WHERE instance_status = 'RUNNING'),
    (SELECT count(*) FROM ji WHERE instance_status = 'SUCCESS'),
    (SELECT count(*) FROM ji WHERE instance_status = 'FAILED'),
    (SELECT count(*) FROM jp WHERE partition_status = 'CREATED'),
    (SELECT count(*) FROM jp WHERE partition_status = 'WAITING'),
    (SELECT count(*) FROM jp WHERE partition_status = 'READY'),
    (SELECT count(*) FROM jp WHERE partition_status = 'RUNNING'),
    (SELECT count(*) FROM jp WHERE partition_status = 'SUCCESS'),
    (SELECT count(*) FROM jp WHERE partition_status = 'FAILED'),
    (SELECT count(*) FROM jt WHERE task_status = 'CREATED'),
    (SELECT count(*) FROM jt WHERE task_status = 'READY'),
    (SELECT count(*) FROM jt WHERE task_status = 'RUNNING'),
    (SELECT count(*) FROM jt WHERE task_status = 'SUCCESS'),
    (SELECT count(*) FROM jt WHERE task_status = 'FAILED'),
    (SELECT count(*) FROM oe WHERE publish_status = 'NEW'),
    (SELECT count(*) FROM oe WHERE publish_status = 'PUBLISHING'),
    (SELECT count(*) FROM oe WHERE publish_status = 'PUBLISHED'),
    (SELECT count(*) FROM oe WHERE publish_status = 'FAILED'),
    (SELECT count(*) FROM tr WHERE request_status IN ('PENDING', 'ACCEPTED')),
    (SELECT count(*) FROM tr WHERE request_status = 'LAUNCHED'),
    (SELECT count(*) FROM fd WHERE dispatch_status = 'CREATED'),
    (SELECT count(*) FROM fd WHERE dispatch_status = 'SENT'),
    (SELECT count(*) FROM fd WHERE dispatch_status = 'ACKED'),
    (SELECT count(*) FROM fd WHERE dispatch_status = 'FAILED'),
    (SELECT count(*) FROM wr WHERE status = 'ONLINE'),
    coalesce((SELECT sum(current_load) FROM wr WHERE status = 'ONLINE'), 0),
    coalesce((SELECT sum(max_concurrent) FROM wr WHERE status = 'ONLINE'), 0),
    coalesce((SELECT floor(max(extract(epoch FROM clock_timestamp() - created_at)))
              FROM jp WHERE partition_status = 'WAITING'), 0)
);
