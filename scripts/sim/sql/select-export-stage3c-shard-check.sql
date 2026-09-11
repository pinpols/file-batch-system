WITH tasks AS (
    SELECT count(*) FILTER (WHERE task.task_status = 'SUCCESS') AS success_tasks
    FROM batch.job_task task
    JOIN batch.job_partition partition ON partition.tenant_id = task.tenant_id AND partition.id = task.job_partition_id
    WHERE partition.tenant_id = :'tenant_id' AND partition.job_instance_id = :'instance_id'::bigint
), files AS (
    SELECT count(*) AS file_count,
           count(*) FILTER (WHERE file_name ~ '_p[1-8]of8\.json$') AS tagged_files,
           coalesce(sum((metadata_json->>'recordCount')::int), 0) AS exported_rows
    FROM batch.file_record
    WHERE tenant_id = :'tenant_id' AND source_ref = :'source_ref' AND source_type = 'GENERATED'
)
SELECT success_tasks || '|' || file_count || '|' || tagged_files || '|' || exported_rows FROM tasks CROSS JOIN files;
