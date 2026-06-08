-- Stage 2e: Import checkpoint crash-resume fixture.
-- SQL only prepares deterministic runtime data; scripts own orchestration and process kill/retry.

UPDATE batch.file_template_config
   SET chunk_size = 50,
       updated_at = now()
 WHERE tenant_id = 'ta'
   AND template_code = 'TA_IMPORT_CUSTOMER_XML_TPL'
   AND is_deleted = false;

DELETE FROM batch.file_error_record
 WHERE tenant_id = 'ta'
   AND raw_record::text LIKE '%S2ECKPT%';
