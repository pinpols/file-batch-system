-- Stage 5c Dispatch source file_record.
-- Required psql variables: batch_no, biz_date, storage_path

WITH old_rows AS (
  SELECT id
  FROM batch.file_record
  WHERE tenant_id = 'tb'
    AND file_code = 'stage5c-dispatch-' || :'batch_no'
)
DELETE FROM batch.file_dispatch_record d
USING old_rows o
WHERE d.file_id = o.id;

DELETE FROM batch.file_record
WHERE tenant_id = 'tb'
  AND file_code = 'stage5c-dispatch-' || :'batch_no';

WITH ins AS (
  INSERT INTO batch.file_record (
      tenant_id, file_code, biz_type, file_category, file_name, original_file_name,
      file_ext, file_format_type, charset, mime_type, file_size_bytes,
      checksum_type, checksum_value, storage_type, storage_path, storage_bucket,
      source_type, source_ref, file_status, biz_date, trace_id, metadata_json
  )
  VALUES (
      'tb',
      'stage5c-dispatch-' || :'batch_no',
      'DISPATCH_STAGE5C',
      'OUTPUT',
      :'batch_no' || '.json',
      :'batch_no' || '.json',
      '.json',
      'JSON',
      'UTF-8',
      'application/json',
      64,
      'NONE',
      null,
      'LOCAL',
      :'storage_path',
      null,
      'GENERATED',
      :'batch_no',
      'GENERATED',
      :'biz_date'::date,
      :'batch_no',
      jsonb_build_object('scenario', 'stage5c-dispatch-channel-matrix')
  )
  RETURNING id
)
SELECT id FROM ins;
