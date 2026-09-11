WITH target_files AS (
    SELECT id, tenant_id
    FROM batch.file_record
    WHERE tenant_id = ANY(string_to_array(:'tenant_ids', ','))
      AND file_category = 'OUTPUT'
      AND source_type = 'GENERATED'
      AND biz_type = ANY(string_to_array(:'biz_types', ','))
      AND source_ref = :'batch_no'
), deleted_dispatch_records AS (
    DELETE FROM batch.file_dispatch_record dispatch
    USING target_files target
    WHERE dispatch.tenant_id = target.tenant_id
      AND dispatch.file_id = target.id
    RETURNING dispatch.id
)
DELETE FROM batch.file_record file
USING target_files target
WHERE file.tenant_id = target.tenant_id
  AND file.id = target.id;
