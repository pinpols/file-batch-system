-- 测试种子数据:覆盖全部导出格式的 file_template_config 记录
-- 覆盖:DELIMITED(CSV/竖线/制表符,各种引号策略)、FIXED_WIDTH、EXCEL、JSON(游标分页)、
--      结算导出(sql_template_export,batch+detail JOIN)
-- 所有记录均使用 tenant_id='t1'。

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

-- 1. CSV 导出(逗号分隔,引号策略 REQUIRED,双引号转义)
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

-- 2. 竖线分隔导出(引号策略 ALL)
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

-- 3. 制表符分隔导出(无表头,反斜杠转义)
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

-- 4. 定长导出(record_length=80)
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

-- 5. Excel 导出(.xlsx,sheet 名 "客户清单",强制最长 31 字符)
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

-- 6. JSON 导出(游标分页,每页 1000 条)
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

-- 7. 加密 GZIP CSV 导出(AES 加密 + GZIP,下载需审批)
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

-- 8. 结算导出 CSV(从 SettlementExportDataPlugin 迁移而来)
--    使用 sql_template_export:在单条 SELECT 中 join batch + detail,
--    复现原先在 describeDelimitedColumns 里硬编码的 9 列。
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

-- 9. 结算导出 JSON(同一查询,JSON 输出)
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
