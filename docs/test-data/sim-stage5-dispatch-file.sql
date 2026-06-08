-- Stage 5 Dispatch source file_record.
-- Required psql variables: batch_no, biz_date

WITH ins AS (
  INSERT INTO batch.file_record (
      tenant_id, file_code, biz_type, file_category, file_name, original_file_name,
      file_ext, file_format_type, charset, mime_type, file_size_bytes,
      checksum_type, checksum_value, storage_type, storage_path, storage_bucket,
      source_type, source_ref, file_status, biz_date, trace_id, metadata_json
  )
  VALUES (
      'tb',
      'stage5-dispatch-' || :'batch_no',
      'TB_DISPATCH_STAGE5_FAIL_ONCE',
      'OUTPUT',
      :'batch_no' || '.json',
      :'batch_no' || '.json',
      '.json',
      'JSON',
      'UTF-8',
      'application/json',
      2,
      'NONE',
      null,
      'LOCAL',
      '/tmp/batch/' || :'batch_no' || '.json',
      null,
      'GENERATED',
      :'batch_no',
      'GENERATED',
      :'biz_date'::date,
      :'batch_no',
      jsonb_build_object('scenario', 'stage5-dispatch-fail-once')
  )
  RETURNING id
)
SELECT id FROM ins;
