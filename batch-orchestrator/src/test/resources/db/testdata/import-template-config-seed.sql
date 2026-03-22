-- Test seed data: file_template_config entries covering all import formats
-- Covers: DELIMITED (CSV, pipe, tab), FIXED_WIDTH, EXCEL, XML, JSON (array + envelope)
-- All records use tenant_id='t1' unless noted.

INSERT INTO batch.file_template_config
  (tenant_id, template_code, template_name, template_type, biz_type,
   file_format_type, charset, target_charset, with_bom,
   delimiter, quote_char, escape_char,
   record_length, header_rows, footer_rows,
   checksum_type, compress_type, encrypt_type,
   field_mappings, validation_rule_set,
   streaming_enabled, page_size, fetch_size, chunk_size,
   content_encryption_enabled, encryption_key_ref,
   preview_masking_enabled, download_requires_approval,
   enabled, version, created_by)
VALUES

-- 1. CSV comma-delimited import (with header, SHA-256 checksum)
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
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test'),

-- 2. Pipe-delimited import
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
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test'),

-- 3. Tab-separated import
('t1', 'IMP-CUSTOMER-TAB', 'Customer Import Tab-separated', 'IMPORT', 'CUSTOMER',
 'DELIMITED', 'UTF-8', 'UTF-8', false,
 '\t', null, null,
 0, 1, 0,
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
 null,
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test'),

-- 4. Fixed-width import (each record exactly 100 chars)
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
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test'),

-- 5. Excel import (sheet "Sheet1", row 1 = header)
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
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test'),

-- 6. XML import (root=customers, record=record)
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
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test'),

-- 7. JSON array import
('t1', 'IMP-CUSTOMER-JSON-ARRAY', 'Customer Import JSON Array', 'IMPORT', 'CUSTOMER',
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
 null,
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test'),

-- 8. JSON envelope import ({"records":[...]})
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
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test'),

-- 9. Encrypted CSV import (AES/BATCHENC, requires approval)
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
 true, 1000, 1000, 500,
 true, 'test-key-ref',
 true, true,
 true, 1, 'test'),

-- 10. GZIP-compressed pipe-delimited import
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
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test')

ON CONFLICT DO NOTHING;
