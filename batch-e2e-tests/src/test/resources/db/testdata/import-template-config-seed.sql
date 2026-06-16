-- 测试种子数据：file_template_config 条目，覆盖全部 import 格式
-- 覆盖：DELIMITED（CSV、pipe、tab）、FIXED_WIDTH、EXCEL、XML、JSON（array + envelope）
-- 除特别说明外，所有记录均使用 tenant_id='t1'。
-- query_param_schema.jdbcMappedImport 把文件字段映射到 biz.customer_account 列。
-- 模板中存在但 customer_account 没有的字段（creditLimit、currencyCode、
-- openDate、remark）已被有意排除在 columnMappings 之外。

ALTER TABLE batch.file_template_config
    ADD COLUMN IF NOT EXISTS load_target_ref VARCHAR(128);

INSERT INTO batch.file_template_config
  (tenant_id, template_code, template_name, template_type, biz_type,
   file_format_type, charset, target_charset, with_bom,
   delimiter, quote_char, escape_char,
   record_length, header_rows, footer_rows,
   checksum_type, compress_type, encrypt_type,
   field_mappings, validation_rule_set, query_param_schema,
   streaming_enabled, page_size, fetch_size, chunk_size,
   content_encryption_enabled, encryption_key_ref,
   preview_masking_enabled, download_requires_approval,
   enabled, version, created_by,
   load_target_ref)
VALUES

-- 1. CSV 逗号分隔导入（含表头，SHA-256 校验和）
('t1', 'IMP-CUSTOMER-CSV', 'Customer Import CSV', 'IMPORT', 'CUSTOMER',
 'DELIMITED', 'UTF-8', 'UTF-8', false,
 ',', '"', '"',
 0, 1, 0,
 'SHA-256', 'NONE', 'NONE',
 '[
   {"name":"customerNo","targetColumn":"customer_no","type":"STRING","required":true,"maxLength":32},
   {"name":"customerName","targetColumn":"customer_name","type":"STRING","required":true,"maxLength":128},
   {"name":"customerType","targetColumn":"customer_type","type":"STRING","required":true,"allowedValues":["PERSONAL","CORPORATE"]},
   {"name":"creditLimit","targetColumn":"credit_limit","type":"DECIMAL","required":true,"minValue":0},
   {"name":"currencyCode","targetColumn":"currency_code","type":"STRING","required":true,"length":3},
   {"name":"email","targetColumn":"email","type":"EMAIL","required":false,"maxLength":256},
   {"name":"phone","targetColumn":"phone","type":"STRING","required":false,"maxLength":20},
   {"name":"status","targetColumn":"status","type":"STRING","required":true,"allowedValues":["ACTIVE","INACTIVE","SUSPENDED"]},
   {"name":"openDate","targetColumn":"open_date","type":"DATE","required":true,"format":"yyyy-MM-dd"},
   {"name":"remark","targetColumn":"remark","type":"STRING","required":false,"maxLength":512}
 ]'::jsonb,
 '{"maxErrorRate":0.05,"stopOnFirstError":false,"duplicateKeyCheck":{"enabled":true,"keys":["customerNo"]}}'::jsonb,
 '{"jdbcMappedImport":{
   "schema":"biz","table":"customer_account","tenantColumn":"tenant_id",
   "columnMappings":[
     {"from":"customerNo",   "to":"customer_no"},
     {"from":"customerName", "to":"customer_name"},
     {"from":"customerType", "to":"customer_type"},
     {"from":"email",        "to":"email"},
     {"from":"phone",        "to":"mobile_no"},
     {"from":"status",       "to":"status"}
   ],
   "conflictColumns":["tenant_id","customer_no"],
   "systemBindings":{
     "source_file_name":"${sourceFileName}",
     "source_batch_no":"${batchNo}",
     "source_trace_id":"${traceId}",
     "created_by":"${workerId}",
     "updated_by":"${workerId}"
   }
 }}'::jsonb,
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test',
 'jdbc_mapped'),

-- 2. pipe 分隔导入
('t1', 'IMP-CUSTOMER-PIPE', 'Customer Import Pipe-delimited', 'IMPORT', 'CUSTOMER',
 'DELIMITED', 'UTF-8', 'UTF-8', false,
 '|', '"', '"',
 0, 1, 0,
 'NONE', 'NONE', 'NONE',
 '[
   {"name":"customerNo","targetColumn":"customer_no","type":"STRING","required":true,"maxLength":32},
   {"name":"customerName","targetColumn":"customer_name","type":"STRING","required":true,"maxLength":128},
   {"name":"customerType","targetColumn":"customer_type","type":"STRING","required":true},
   {"name":"creditLimit","targetColumn":"credit_limit","type":"DECIMAL","required":true},
   {"name":"currencyCode","targetColumn":"currency_code","type":"STRING","required":true},
   {"name":"email","targetColumn":"email","type":"EMAIL","required":false},
   {"name":"phone","targetColumn":"phone","type":"STRING","required":false},
   {"name":"status","targetColumn":"status","type":"STRING","required":true},
   {"name":"openDate","targetColumn":"open_date","type":"DATE","required":true,"format":"yyyy-MM-dd"},
   {"name":"remark","targetColumn":"remark","type":"STRING","required":false}
 ]'::jsonb,
 null,
 '{"jdbcMappedImport":{
   "schema":"biz","table":"customer_account","tenantColumn":"tenant_id",
   "columnMappings":[
     {"from":"customerNo",   "to":"customer_no"},
     {"from":"customerName", "to":"customer_name"},
     {"from":"customerType", "to":"customer_type"},
     {"from":"email",        "to":"email"},
     {"from":"phone",        "to":"mobile_no"},
     {"from":"status",       "to":"status"}
   ],
   "conflictColumns":["tenant_id","customer_no"],
   "systemBindings":{
     "source_file_name":"${sourceFileName}",
     "source_batch_no":"${batchNo}",
     "source_trace_id":"${traceId}",
     "created_by":"${workerId}",
     "updated_by":"${workerId}"
   }
 }}'::jsonb,
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test',
 'jdbc_mapped'),

-- 3. tab 分隔导入
('t1', 'IMP-CUSTOMER-TAB', 'Customer Import Tab-separated', 'IMPORT', 'CUSTOMER',
 'DELIMITED', 'UTF-8', 'UTF-8', false,
 '\t', null, null,
 0, 1, 0,
 'NONE', 'NONE', 'NONE',
 '[
   {"name":"customerNo","targetColumn":"customer_no","type":"STRING","required":true},
   {"name":"customerName","targetColumn":"customer_name","type":"STRING","required":true},
   {"name":"customerType","targetColumn":"customer_type","type":"STRING","required":true},
   {"name":"certificateNo","targetColumn":"certificate_no","type":"STRING","required":false},
   {"name":"mobileNo","targetColumn":"mobile_no","type":"STRING","required":false},
   {"name":"email","targetColumn":"email","type":"EMAIL","required":false},
   {"name":"status","targetColumn":"status","type":"STRING","required":true}
 ]'::jsonb,
 null,
 '{"jdbcMappedImport":{
   "schema":"biz","table":"customer_account","tenantColumn":"tenant_id",
   "columnMappings":[
     {"from":"customerNo",    "to":"customer_no"},
     {"from":"customerName",  "to":"customer_name"},
     {"from":"customerType",  "to":"customer_type"},
     {"from":"certificateNo", "to":"certificate_no"},
     {"from":"mobileNo",      "to":"mobile_no"},
     {"from":"email",         "to":"email"},
     {"from":"status",        "to":"status"}
   ],
   "conflictColumns":["tenant_id","customer_no"],
   "systemBindings":{
     "source_file_name":"${sourceFileName}",
     "source_batch_no":"${batchNo}",
     "source_trace_id":"${traceId}",
     "created_by":"${workerId}",
     "updated_by":"${workerId}"
   }
 }}'::jsonb,
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test',
 'jdbc_mapped'),

-- 4. 定长导入（每条记录恰好 100 字符）
('t1', 'IMP-CUSTOMER-FW', 'Customer Import Fixed-Width', 'IMPORT', 'CUSTOMER',
 'FIXED_WIDTH', 'UTF-8', 'UTF-8', false,
 null, null, null,
 100, 0, 0,
 'NONE', 'NONE', 'NONE',
 '[
   {"name":"customerNo","targetColumn":"customer_no","type":"STRING","required":true,"offset":0,"length":4},
   {"name":"customerName","targetColumn":"customer_name","type":"STRING","required":true,"offset":4,"length":20},
   {"name":"customerType","targetColumn":"customer_type","type":"STRING","required":true,"offset":24,"length":9},
   {"name":"creditLimit","targetColumn":"credit_limit","type":"DECIMAL","required":true,"offset":33,"length":10,"format":"0000000.00"},
   {"name":"currencyCode","targetColumn":"currency_code","type":"STRING","required":true,"offset":43,"length":3},
   {"name":"email","targetColumn":"email","type":"STRING","required":false,"offset":46,"length":24},
   {"name":"phone","targetColumn":"phone","type":"STRING","required":false,"offset":70,"length":12},
   {"name":"status","targetColumn":"status","type":"STRING","required":true,"offset":82,"length":8},
   {"name":"openDate","targetColumn":"open_date","type":"DATE","required":true,"offset":90,"length":8,"format":"yyyyMMdd"},
   {"name":"remark","targetColumn":"remark","type":"STRING","required":false,"offset":98,"length":3}
 ]'::jsonb,
 null,
 '{"jdbcMappedImport":{
   "schema":"biz","table":"customer_account","tenantColumn":"tenant_id",
   "columnMappings":[
     {"from":"customerNo",   "to":"customer_no"},
     {"from":"customerName", "to":"customer_name"},
     {"from":"customerType", "to":"customer_type"},
     {"from":"email",        "to":"email"},
     {"from":"phone",        "to":"mobile_no"},
     {"from":"status",       "to":"status"}
   ],
   "conflictColumns":["tenant_id","customer_no"],
   "systemBindings":{
     "source_file_name":"${sourceFileName}",
     "source_batch_no":"${batchNo}",
     "source_trace_id":"${traceId}",
     "created_by":"${workerId}",
     "updated_by":"${workerId}"
   }
 }}'::jsonb,
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test',
 'jdbc_mapped'),

-- 5. Excel 导入（sheet "Sheet1"，第 1 行为表头）
('t1', 'IMP-CUSTOMER-EXCEL', 'Customer Import Excel', 'IMPORT', 'CUSTOMER',
 'EXCEL', 'UTF-8', 'UTF-8', false,
 null, null, null,
 0, 1, 0,
 'NONE', 'NONE', 'NONE',
 '[
   {"name":"customerNo","targetColumn":"customer_no","type":"STRING","required":true,"colIndex":0},
   {"name":"customerName","targetColumn":"customer_name","type":"STRING","required":true,"colIndex":1},
   {"name":"customerType","targetColumn":"customer_type","type":"STRING","required":true,"colIndex":2},
   {"name":"creditLimit","targetColumn":"credit_limit","type":"DECIMAL","required":true,"colIndex":3},
   {"name":"currencyCode","targetColumn":"currency_code","type":"STRING","required":true,"colIndex":4},
   {"name":"email","targetColumn":"email","type":"EMAIL","required":false,"colIndex":5},
   {"name":"phone","targetColumn":"phone","type":"STRING","required":false,"colIndex":6},
   {"name":"status","targetColumn":"status","type":"STRING","required":true,"colIndex":7},
   {"name":"openDate","targetColumn":"open_date","type":"DATE","required":true,"colIndex":8,"format":"yyyy-MM-dd"},
   {"name":"remark","targetColumn":"remark","type":"STRING","required":false,"colIndex":9}
 ]'::jsonb,
 '{"sheetName":"Sheet1","maxErrorRate":0.1}'::jsonb,
 '{"jdbcMappedImport":{
   "schema":"biz","table":"customer_account","tenantColumn":"tenant_id",
   "columnMappings":[
     {"from":"customerNo",   "to":"customer_no"},
     {"from":"customerName", "to":"customer_name"},
     {"from":"customerType", "to":"customer_type"},
     {"from":"email",        "to":"email"},
     {"from":"phone",        "to":"mobile_no"},
     {"from":"status",       "to":"status"}
   ],
   "conflictColumns":["tenant_id","customer_no"],
   "systemBindings":{
     "source_file_name":"${sourceFileName}",
     "source_batch_no":"${batchNo}",
     "source_trace_id":"${traceId}",
     "created_by":"${workerId}",
     "updated_by":"${workerId}"
   }
 }}'::jsonb,
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test',
 'jdbc_mapped'),

-- 6. XML 导入（root=customers，record=record）
('t1', 'IMP-CUSTOMER-XML', 'Customer Import XML', 'IMPORT', 'CUSTOMER',
 'XML', 'UTF-8', 'UTF-8', false,
 null, null, null,
 0, 0, 0,
 'NONE', 'NONE', 'NONE',
 '[
   {"name":"customerNo","targetColumn":"customer_no","type":"STRING","required":true,"xmlPath":"customerNo"},
   {"name":"customerName","targetColumn":"customer_name","type":"STRING","required":true,"xmlPath":"customerName"},
   {"name":"customerType","targetColumn":"customer_type","type":"STRING","required":true,"xmlPath":"customerType"},
   {"name":"creditLimit","targetColumn":"credit_limit","type":"DECIMAL","required":true,"xmlPath":"creditLimit"},
   {"name":"currencyCode","targetColumn":"currency_code","type":"STRING","required":true,"xmlPath":"currencyCode"},
   {"name":"email","targetColumn":"email","type":"EMAIL","required":false,"xmlPath":"email"},
   {"name":"phone","targetColumn":"phone","type":"STRING","required":false,"xmlPath":"phone"},
   {"name":"status","targetColumn":"status","type":"STRING","required":true,"xmlPath":"status"},
   {"name":"openDate","targetColumn":"open_date","type":"DATE","required":true,"xmlPath":"openDate","format":"yyyy-MM-dd"},
   {"name":"remark","targetColumn":"remark","type":"STRING","required":false,"xmlPath":"remark"}
 ]'::jsonb,
 '{"rootElement":"customers","recordElement":"record"}'::jsonb,
 '{"jdbcMappedImport":{
   "schema":"biz","table":"customer_account","tenantColumn":"tenant_id",
   "columnMappings":[
     {"from":"customerNo",   "to":"customer_no"},
     {"from":"customerName", "to":"customer_name"},
     {"from":"customerType", "to":"customer_type"},
     {"from":"email",        "to":"email"},
     {"from":"phone",        "to":"mobile_no"},
     {"from":"status",       "to":"status"}
   ],
   "conflictColumns":["tenant_id","customer_no"],
   "systemBindings":{
     "source_file_name":"${sourceFileName}",
     "source_batch_no":"${batchNo}",
     "source_trace_id":"${traceId}",
     "created_by":"${workerId}",
     "updated_by":"${workerId}"
   }
 }}'::jsonb,
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test',
 'jdbc_mapped'),

-- 7. JSON array 导入
('t1', 'IMP-CUSTOMER-JSON-ARRAY', 'Customer Import JSON Array', 'IMPORT', 'CUSTOMER',
 'JSON', 'UTF-8', 'UTF-8', false,
 null, null, null,
 0, 0, 0,
 'NONE', 'NONE', 'NONE',
 '[
   {"name":"customerNo","targetColumn":"customer_no","type":"STRING","required":true},
   {"name":"customerName","targetColumn":"customer_name","type":"STRING","required":true},
   {"name":"customerType","targetColumn":"customer_type","type":"STRING","required":true},
   {"name":"creditLimit","targetColumn":"credit_limit","type":"DECIMAL","required":false},
   {"name":"currencyCode","targetColumn":"currency_code","type":"STRING","required":false},
   {"name":"email","targetColumn":"email","type":"EMAIL","required":false},
   {"name":"phone","targetColumn":"phone","type":"STRING","required":false},
   {"name":"status","targetColumn":"status","type":"STRING","required":true},
   {"name":"openDate","targetColumn":"open_date","type":"DATE","required":false,"format":"yyyy-MM-dd"},
   {"name":"remark","targetColumn":"remark","type":"STRING","required":false}
 ]'::jsonb,
 null,
 '{"jdbcMappedImport":{
   "schema":"biz","table":"customer_account","tenantColumn":"tenant_id",
   "columnMappings":[
     {"from":"customerNo",   "to":"customer_no"},
     {"from":"customerName", "to":"customer_name"},
     {"from":"customerType", "to":"customer_type"},
     {"from":"email",        "to":"email"},
     {"from":"phone",        "to":"mobile_no"},
     {"from":"status",       "to":"status"}
   ],
   "conflictColumns":["tenant_id","customer_no"],
   "systemBindings":{
     "source_file_name":"${sourceFileName}",
     "source_batch_no":"${batchNo}",
     "source_trace_id":"${traceId}",
     "created_by":"${workerId}",
     "updated_by":"${workerId}"
   }
 }}'::jsonb,
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test',
 'jdbc_mapped'),

-- 7b. JSON array 导入，带严格校验失败语义，用于 E2E 负向路径
('t1', 'IMP-CUSTOMER-JSON-ARRAY-STRICT', 'Customer Import JSON Array Strict', 'IMPORT', 'CUSTOMER',
 'JSON', 'UTF-8', 'UTF-8', false,
 null, null, null,
 0, 0, 0,
 'NONE', 'NONE', 'NONE',
 '[
   {"name":"customerNo","targetColumn":"customer_no","type":"STRING","required":true},
   {"name":"customerName","targetColumn":"customer_name","type":"STRING","required":true},
   {"name":"customerType","targetColumn":"customer_type","type":"STRING","required":true},
   {"name":"creditLimit","targetColumn":"credit_limit","type":"DECIMAL","required":false},
   {"name":"currencyCode","targetColumn":"currency_code","type":"STRING","required":false},
   {"name":"email","targetColumn":"email","type":"EMAIL","required":false},
   {"name":"phone","targetColumn":"phone","type":"STRING","required":false},
   {"name":"status","targetColumn":"status","type":"STRING","required":true},
   {"name":"openDate","targetColumn":"open_date","type":"DATE","required":false,"format":"yyyy-MM-dd"},
   {"name":"remark","targetColumn":"remark","type":"STRING","required":false}
 ]'::jsonb,
 '{"fieldRules":{
    "customerNo":{"required":true,"errorCode":"IMPORT_VALIDATE_REQUIRED_FATAL"},
    "customerName":{"required":true,"errorCode":"IMPORT_VALIDATE_REQUIRED_FATAL"}
  }}'::jsonb,
 '{"jdbcMappedImport":{
   "schema":"biz","table":"customer_account","tenantColumn":"tenant_id",
   "columnMappings":[
     {"from":"customerNo",   "to":"customer_no"},
     {"from":"customerName", "to":"customer_name"},
     {"from":"customerType", "to":"customer_type"},
     {"from":"email",        "to":"email"},
     {"from":"phone",        "to":"mobile_no"},
     {"from":"status",       "to":"status"}
   ],
   "conflictColumns":["tenant_id","customer_no"],
   "systemBindings":{
     "source_file_name":"${sourceFileName}",
     "source_batch_no":"${batchNo}",
     "source_trace_id":"${traceId}",
     "created_by":"${workerId}",
     "updated_by":"${workerId}"
   }
 }}'::jsonb,
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test',
 'jdbc_mapped'),

-- 8. JSON envelope 导入（{"records":[...]}）
('t1', 'IMP-CUSTOMER-JSON-ENV', 'Customer Import JSON Envelope', 'IMPORT', 'CUSTOMER',
 'JSON', 'UTF-8', 'UTF-8', false,
 null, null, null,
 0, 0, 0,
 'NONE', 'NONE', 'NONE',
 '[
   {"name":"customerNo","targetColumn":"customer_no","type":"STRING","required":true},
   {"name":"customerName","targetColumn":"customer_name","type":"STRING","required":true},
   {"name":"customerType","targetColumn":"customer_type","type":"STRING","required":true},
   {"name":"creditLimit","targetColumn":"credit_limit","type":"DECIMAL","required":true},
   {"name":"currencyCode","targetColumn":"currency_code","type":"STRING","required":true},
   {"name":"email","targetColumn":"email","type":"EMAIL","required":false},
   {"name":"phone","targetColumn":"phone","type":"STRING","required":false},
   {"name":"status","targetColumn":"status","type":"STRING","required":true},
   {"name":"openDate","targetColumn":"open_date","type":"DATE","required":true,"format":"yyyy-MM-dd"},
   {"name":"remark","targetColumn":"remark","type":"STRING","required":false}
 ]'::jsonb,
 '{"envelopeKey":"records"}'::jsonb,
 '{"jdbcMappedImport":{
   "schema":"biz","table":"customer_account","tenantColumn":"tenant_id",
   "columnMappings":[
     {"from":"customerNo",   "to":"customer_no"},
     {"from":"customerName", "to":"customer_name"},
     {"from":"customerType", "to":"customer_type"},
     {"from":"email",        "to":"email"},
     {"from":"phone",        "to":"mobile_no"},
     {"from":"status",       "to":"status"}
   ],
   "conflictColumns":["tenant_id","customer_no"],
   "systemBindings":{
     "source_file_name":"${sourceFileName}",
     "source_batch_no":"${batchNo}",
     "source_trace_id":"${traceId}",
     "created_by":"${workerId}",
     "updated_by":"${workerId}"
   }
 }}'::jsonb,
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test',
 'jdbc_mapped'),

-- 9. 加密 CSV 导入（AES/BATCHENC，需审批）
('t1', 'IMP-CUSTOMER-CSV-ENC', 'Customer Import CSV Encrypted', 'IMPORT', 'CUSTOMER',
 'DELIMITED', 'UTF-8', 'UTF-8', false,
 ',', '"', '"',
 0, 1, 0,
 'SHA-256', 'NONE', 'AES',
 '[
   {"name":"customerNo","targetColumn":"customer_no","type":"STRING","required":true},
   {"name":"customerName","targetColumn":"customer_name","type":"STRING","required":true},
   {"name":"creditLimit","targetColumn":"credit_limit","type":"DECIMAL","required":true},
   {"name":"status","targetColumn":"status","type":"STRING","required":true}
 ]'::jsonb,
 '{"maxErrorRate":0.01}'::jsonb,
 '{"jdbcMappedImport":{
   "schema":"biz","table":"customer_account","tenantColumn":"tenant_id",
   "columnMappings":[
     {"from":"customerNo",   "to":"customer_no"},
     {"from":"customerName", "to":"customer_name"},
     {"from":"status",       "to":"status"}
   ],
   "conflictColumns":["tenant_id","customer_no"],
   "systemBindings":{
     "source_file_name":"${sourceFileName}",
     "source_batch_no":"${batchNo}",
     "source_trace_id":"${traceId}",
     "created_by":"${workerId}",
     "updated_by":"${workerId}"
   }
 }}'::jsonb,
 true, 1000, 1000, 500,
 true, 'test-key-ref',
 true, true,
 true, 1, 'test',
 'jdbc_mapped'),

-- 10. GZIP 压缩的 pipe 分隔导入
('t1', 'IMP-CUSTOMER-PIPE-GZ', 'Customer Import Pipe-delimited GZIP', 'IMPORT', 'CUSTOMER',
 'DELIMITED', 'UTF-8', 'UTF-8', false,
 '|', '"', '"',
 0, 1, 0,
 'MD5', 'GZIP', 'NONE',
 '[
   {"name":"customerNo","targetColumn":"customer_no","type":"STRING","required":true},
   {"name":"customerName","targetColumn":"customer_name","type":"STRING","required":true},
   {"name":"status","targetColumn":"status","type":"STRING","required":true}
 ]'::jsonb,
 null,
 '{"jdbcMappedImport":{
   "schema":"biz","table":"customer_account","tenantColumn":"tenant_id",
   "columnMappings":[
     {"from":"customerNo",   "to":"customer_no"},
     {"from":"customerName", "to":"customer_name"},
     {"from":"status",       "to":"status"}
   ],
   "conflictColumns":["tenant_id","customer_no"],
   "systemBindings":{
     "source_file_name":"${sourceFileName}",
     "source_batch_no":"${batchNo}",
     "source_trace_id":"${traceId}",
     "created_by":"${workerId}",
     "updated_by":"${workerId}"
   }
 }}'::jsonb,
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test',
 'jdbc_mapped')

ON CONFLICT DO NOTHING;
