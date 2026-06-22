DELETE FROM batch.file_error_record
WHERE tenant_id = 'ta'
  AND raw_record::text LIKE '%S2DSKIP%';
