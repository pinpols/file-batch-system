-- Test seed data: file_template_config entries covering all export formats
-- Covers: DELIMITED (CSV/pipe/tab, quote policies), FIXED_WIDTH, EXCEL, JSON (cursor pagination),
--         Settlement export (sql_template_export with batch+detail JOIN)
-- All records use tenant_id='t1'.

INSERT INTO batch.file_template_config
  (tenant_id, template_code, template_name, template_type, biz_type,
   file_format_type, charset, target_charset, with_bom,
   delimiter, quote_char, escape_char,
   record_length, header_rows, footer_rows,
   checksum_type, compress_type, encrypt_type,
   naming_rule, field_mappings, default_query_code, default_query_sql, query_param_schema,
   streaming_enabled, page_size, fetch_size, chunk_size,
   content_encryption_enabled, encryption_key_ref,
   preview_masking_enabled, download_requires_approval,
   enabled, version, created_by,
   export_data_ref)
VALUES

-- 1. CSV export (comma, quote policy REQUIRED, double-quote escape)
('t1', 'EXP-CUSTOMER-CSV', 'Customer Export CSV', 'EXPORT', 'CUSTOMER',
 'DELIMITED', 'UTF-8', 'UTF-8', false,
 ',', '"', '"',
 0, 0, 0,
 'SHA-256', 'NONE', 'NONE',
 'customers_{bizDate}_{seq}.csv',
 '[
   {"name":"customerNo","sourceColumn":"customer_no","type":"STRING","header":"customerNo"},
   {"name":"customerName","sourceColumn":"customer_name","type":"STRING","header":"customerName"},
   {"name":"customerType","sourceColumn":"customer_type","type":"STRING","header":"customerType"},
   {"name":"creditLimit","sourceColumn":"credit_limit","type":"DECIMAL","header":"creditLimit","format":"#,##0.00"},
   {"name":"currencyCode","sourceColumn":"currency_code","type":"STRING","header":"currencyCode"},
   {"name":"email","sourceColumn":"email","type":"STRING","header":"email"},
   {"name":"status","sourceColumn":"status","type":"STRING","header":"status"},
   {"name":"openDate","sourceColumn":"open_date","type":"DATE","header":"openDate","format":"yyyy-MM-dd"}
 ]'::jsonb,
 'QUERY_CUSTOMER_LIST',
 'SELECT customer_no, customer_name, customer_type, credit_limit, currency_code, email, status, open_date FROM biz.customer_account WHERE tenant_id = :tenantId AND (:status IS NULL OR status = :status)',
 '{"type":"object","properties":{"tenantId":{"type":"string","required":true},"status":{"type":"string","required":false}}}'::jsonb,
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test',
 'sql_template_export'),

-- 2. Pipe-delimited export (ALL quote policy)
('t1', 'EXP-CUSTOMER-PIPE', 'Customer Export Pipe-delimited', 'EXPORT', 'CUSTOMER',
 'DELIMITED', 'UTF-8', 'UTF-8', false,
 '|', '"', '"',
 0, 0, 0,
 'NONE', 'NONE', 'NONE',
 'customers_{bizDate}.txt',
 '[
   {"name":"customerNo","sourceColumn":"customer_no","type":"STRING","header":"customerNo","quotePolicy":"ALL"},
   {"name":"customerName","sourceColumn":"customer_name","type":"STRING","header":"customerName","quotePolicy":"ALL"},
   {"name":"creditLimit","sourceColumn":"credit_limit","type":"DECIMAL","header":"creditLimit","quotePolicy":"ALL"}
 ]'::jsonb,
 null, null, null,
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test',
 null),

-- 3. Tab-separated export (no header, backslash escape)
('t1', 'EXP-CUSTOMER-TAB-NOHEADER', 'Customer Export Tab No-Header', 'EXPORT', 'CUSTOMER',
 'DELIMITED', 'UTF-8', 'UTF-8', false,
 '\t', null, E'\\',
 0, 0, 0,
 'NONE', 'NONE', 'NONE',
 'customers_noheader_{seq}.tsv',
 '[
   {"name":"customerNo","sourceColumn":"customer_no","type":"STRING"},
   {"name":"customerName","sourceColumn":"customer_name","type":"STRING"},
   {"name":"status","sourceColumn":"status","type":"STRING"}
 ]'::jsonb,
 null, null, null,
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test',
 null),

-- 4. Fixed-width export (record_length=80)
('t1', 'EXP-CUSTOMER-FW', 'Customer Export Fixed-Width', 'EXPORT', 'CUSTOMER',
 'FIXED_WIDTH', 'UTF-8', 'UTF-8', false,
 null, null, null,
 80, 0, 0,
 'NONE', 'NONE', 'NONE',
 'customers_{bizDate}.dat',
 '[
   {"name":"customerNo","sourceColumn":"customer_no","type":"STRING","offset":0,"width":4,"rightAlign":false,"padChar":" "},
   {"name":"customerName","sourceColumn":"customer_name","type":"STRING","offset":4,"width":20,"rightAlign":false,"padChar":" "},
   {"name":"customerType","sourceColumn":"customer_type","type":"STRING","offset":24,"width":9,"rightAlign":false,"padChar":" "},
   {"name":"creditLimit","sourceColumn":"credit_limit","type":"DECIMAL","offset":33,"width":12,"rightAlign":true,"padChar":"0"},
   {"name":"currencyCode","sourceColumn":"currency_code","type":"STRING","offset":45,"width":3,"rightAlign":false,"padChar":" "},
   {"name":"status","sourceColumn":"status","type":"STRING","offset":48,"width":10,"rightAlign":false,"padChar":" "},
   {"name":"openDate","sourceColumn":"open_date","type":"DATE","offset":58,"width":8,"format":"yyyyMMdd"},
   {"name":"filler","sourceColumn":null,"type":"CONST","offset":66,"width":14,"constValue":"              "}
 ]'::jsonb,
 null, null, null,
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test',
 null),

-- 5. Excel export (.xlsx, sheet name "客户清单", max 31 chars enforced)
('t1', 'EXP-CUSTOMER-EXCEL', 'Customer Export Excel', 'EXPORT', 'CUSTOMER',
 'EXCEL', 'UTF-8', 'UTF-8', false,
 null, null, null,
 0, 0, 0,
 'NONE', 'NONE', 'NONE',
 'customers_{bizDate}.xlsx',
 '[
   {"name":"customerNo","sourceColumn":"customer_no","type":"STRING","header":"客户编号","colIndex":0},
   {"name":"customerName","sourceColumn":"customer_name","type":"STRING","header":"客户名称","colIndex":1},
   {"name":"customerType","sourceColumn":"customer_type","type":"STRING","header":"客户类型","colIndex":2},
   {"name":"creditLimit","sourceColumn":"credit_limit","type":"DECIMAL","header":"授信额度","colIndex":3,"format":"#,##0.00"},
   {"name":"currencyCode","sourceColumn":"currency_code","type":"STRING","header":"币种","colIndex":4},
   {"name":"email","sourceColumn":"email","type":"STRING","header":"邮箱","colIndex":5},
   {"name":"status","sourceColumn":"status","type":"STRING","header":"状态","colIndex":6},
   {"name":"openDate","sourceColumn":"open_date","type":"DATE","header":"开户日期","colIndex":7,"format":"yyyy-MM-dd"}
 ]'::jsonb,
 null, null,
 '{"sheetName":"客户清单","freezeHeaderRow":true,"autoFilter":true}'::jsonb,
 true, 50000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test',
 null),

-- 6. JSON export (cursor pagination, 1000 per page)
('t1', 'EXP-CUSTOMER-JSON', 'Customer Export JSON', 'EXPORT', 'CUSTOMER',
 'JSON', 'UTF-8', 'UTF-8', false,
 null, null, null,
 0, 0, 0,
 'NONE', 'NONE', 'NONE',
 'customers_{bizDate}.json',
 '[
   {"name":"customerNo","sourceColumn":"customer_no","type":"STRING"},
   {"name":"customerName","sourceColumn":"customer_name","type":"STRING"},
   {"name":"customerType","sourceColumn":"customer_type","type":"STRING"},
   {"name":"creditLimit","sourceColumn":"credit_limit","type":"DECIMAL"},
   {"name":"currencyCode","sourceColumn":"currency_code","type":"STRING"},
   {"name":"status","sourceColumn":"status","type":"STRING"},
   {"name":"openDate","sourceColumn":"open_date","type":"DATE","format":"yyyy-MM-dd"}
 ]'::jsonb,
 null, null, null,
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'test',
 null),

-- 7. Encrypted GZIP CSV export (AES encryption + GZIP, download requires approval)
('t1', 'EXP-CUSTOMER-CSV-ENC-GZ', 'Customer Export CSV Encrypted GZIP', 'EXPORT', 'CUSTOMER',
 'DELIMITED', 'UTF-8', 'UTF-8', false,
 ',', '"', '"',
 0, 0, 0,
 'SHA-256', 'GZIP', 'AES',
 'customers_sensitive_{bizDate}.csv.gz',
 '[
   {"name":"customerNo","sourceColumn":"customer_no","type":"STRING"},
   {"name":"customerName","sourceColumn":"customer_name","type":"STRING"},
   {"name":"creditLimit","sourceColumn":"credit_limit","type":"DECIMAL"},
   {"name":"status","sourceColumn":"status","type":"STRING"}
 ]'::jsonb,
 null, null, null,
 true, 1000, 1000, 500,
 true, 'test-key-ref',
 true, true,
 true, 1, 'test',
 null),

-- 8. Settlement export CSV (migrated from SettlementExportDataPlugin)
--    Uses sql_template_export: batch + detail joined in a single SELECT,
--    reproducing the 9 columns formerly hardcoded in describeDelimitedColumns.
('t1', 'EXP-SETTLEMENT-CSV', 'Settlement Export CSV', 'EXPORT', 'SETTLEMENT',
 'DELIMITED', 'UTF-8', 'UTF-8', false,
 ',', '"', '"',
 0, 0, 0,
 'SHA-256', 'NONE', 'NONE',
 'settlement_{bizDate}_{batchNo}.csv',
 '[
   {"name":"batchNo",        "sourceColumn":"batch_no",         "type":"STRING",  "header":"batchNo"},
   {"name":"bizDate",        "sourceColumn":"biz_date",         "type":"DATE",    "header":"bizDate",    "format":"yyyy-MM-dd"},
   {"name":"settlementNo",   "sourceColumn":"settlement_no",    "type":"STRING",  "header":"settlementNo"},
   {"name":"customerNo",     "sourceColumn":"customer_no",      "type":"STRING",  "header":"customerNo"},
   {"name":"grossAmount",    "sourceColumn":"gross_amount",     "type":"DECIMAL", "header":"grossAmount", "format":"#,##0.00"},
   {"name":"feeAmount",      "sourceColumn":"fee_amount",       "type":"DECIMAL", "header":"feeAmount",   "format":"#,##0.00"},
   {"name":"netAmount",      "sourceColumn":"net_amount",       "type":"DECIMAL", "header":"netAmount",   "format":"#,##0.00"},
   {"name":"currency",       "sourceColumn":"currency",         "type":"STRING",  "header":"currency"},
   {"name":"status",         "sourceColumn":"settlement_status","type":"STRING",  "header":"status"}
 ]'::jsonb,
 'QUERY_SETTLEMENT_DETAIL',
 'SELECT sb.batch_no, sb.biz_date,
         sd.settlement_no, sd.customer_no,
         sd.gross_amount,  sd.fee_amount, sd.net_amount,
         sd.currency,      sd.settlement_status,
         sd.id
  FROM   biz.settlement_detail sd
  JOIN   biz.settlement_batch  sb ON sb.id = sd.batch_id
  WHERE  sb.tenant_id = :tenantId
    AND  sb.batch_no  = :batchNo',
 '{"sqlTemplateExport":{"cursorColumn":"id"}}'::jsonb,
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'migration',
 'sql_template_export'),

-- 9. Settlement export JSON (same query, JSON output)
('t1', 'EXP-SETTLEMENT-JSON', 'Settlement Export JSON', 'EXPORT', 'SETTLEMENT',
 'JSON', 'UTF-8', 'UTF-8', false,
 null, null, null,
 0, 0, 0,
 'SHA-256', 'NONE', 'NONE',
 'settlement_{bizDate}_{batchNo}.json',
 null,
 'QUERY_SETTLEMENT_DETAIL',
 'SELECT sb.batch_no, sb.biz_date,
         sd.settlement_no, sd.customer_no,
         sd.gross_amount,  sd.fee_amount, sd.net_amount,
         sd.currency,      sd.settlement_status,
         sd.id
  FROM   biz.settlement_detail sd
  JOIN   biz.settlement_batch  sb ON sb.id = sd.batch_id
  WHERE  sb.tenant_id = :tenantId
    AND  sb.batch_no  = :batchNo',
 '{"sqlTemplateExport":{"cursorColumn":"id"}}'::jsonb,
 true, 1000, 1000, 500,
 false, null,
 false, false,
 true, 1, 'migration',
 'sql_template_export')

ON CONFLICT DO NOTHING;
