-- DANGER: 删除指定 sim 批次生成的文件记录。仅限本地 sim 库，必须提供租户、作业和 batchNo 条件。
\set ON_ERROR_STOP on
BEGIN;

CREATE TEMP TABLE sim_output_file_cleanup_targets ON COMMIT DROP AS
SELECT id, tenant_id
FROM batch.file_record
WHERE tenant_id = ANY(string_to_array(:'tenant_ids', ','))
  AND file_category = 'OUTPUT'
  AND source_type = 'GENERATED'
  AND biz_type = ANY(string_to_array(:'biz_types', ','))
  AND source_ref = :'batch_no';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM batch.job_partition partition
        JOIN sim_output_file_cleanup_targets target
          ON target.tenant_id = partition.tenant_id
         AND target.id = partition.source_file_id
    ) THEN
        RAISE EXCEPTION 'SIM output file cleanup refused: a hot job_partition still references a target file; archive or remove the owning runtime first';
    END IF;
END
$$;

WITH deleted_dispatch_records AS (
    DELETE FROM batch.file_dispatch_record dispatch
    USING sim_output_file_cleanup_targets target
    WHERE dispatch.tenant_id = target.tenant_id
      AND dispatch.file_id = target.id
    RETURNING dispatch.id
)
DELETE FROM batch.file_record file
USING sim_output_file_cleanup_targets target
WHERE file.tenant_id = target.tenant_id
  AND file.id = target.id;

COMMIT;
-- DANGER: 删除指定 sim 批次生成的文件记录。仅限本地 sim 库，必须提供租户、作业和 batchNo 条件。
