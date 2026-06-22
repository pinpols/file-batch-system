-- Stage 2e：Import checkpoint 崩溃续跑 fixture。
-- SQL 只准备确定性的运行态数据；编排与进程 kill/retry 由脚本负责。

UPDATE batch.file_template_config
   SET chunk_size = 1,
       updated_at = now()
 WHERE tenant_id = 'ta'
   AND template_code = 'TA_IMPORT_CUSTOMER_XML_TPL'
   AND is_deleted = false;

DELETE FROM batch.file_error_record
 WHERE tenant_id = 'ta'
   AND raw_record::text LIKE '%S2ECKPT%';

DELETE FROM batch.pipeline_progress
 WHERE tenant_id = 'ta'
   AND stage = 'LOAD';
