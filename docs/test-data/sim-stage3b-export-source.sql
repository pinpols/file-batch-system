-- Stage 3b Export source data.
-- Required psql variable: batch_no

-- 先清理本 stage 专用的 EXP3B-% 行再 seed:固定 40 行(generate_series 1..40),保证 Stage 3b
-- 的 4 分片 keyset 导出行数断言(40)精确可复现。原仅 ON CONFLICT 幂等,无法清除历史更大范围
-- seed 残留(如旧版曾生成 1..80),导致行数累积、断言 4|4|4|40 漂移成 80。范围限 tenant=ta + EXP3B-%。
DELETE FROM biz.customer_account WHERE tenant_id = 'ta' AND customer_no LIKE 'EXP3B-%';

INSERT INTO biz.customer_account (
    tenant_id, customer_no, customer_name, customer_type, certificate_no,
    mobile_no, email, status, source_file_name, source_batch_no, source_trace_id,
    created_by, updated_by
)
SELECT 'ta',
       'EXP3B-' || lpad(gs::text, 4, '0'),
       'Export Stage3b ' || gs,
       'PERSONAL',
       'EXP3BCERT' || lpad(gs::text, 4, '0'),
       '1392000' || lpad(gs::text, 4, '0'),
       'exp3b' || gs || '@sim.io',
       CASE WHEN gs % 3 = 0 THEN 'INACTIVE' ELSE 'ACTIVE' END,
       'stage3b-export-seed.csv',
       :'batch_no',
       'stage3b-export-seed',
       'sim-e2e',
       'sim-e2e'
FROM generate_series(1, 40) AS gs
ON CONFLICT (tenant_id, customer_no) DO UPDATE
SET customer_name = EXCLUDED.customer_name,
    customer_type = EXCLUDED.customer_type,
    certificate_no = EXCLUDED.certificate_no,
    mobile_no = EXCLUDED.mobile_no,
    email = EXCLUDED.email,
    status = EXCLUDED.status,
    source_file_name = EXCLUDED.source_file_name,
    source_batch_no = EXCLUDED.source_batch_no,
    source_trace_id = EXCLUDED.source_trace_id,
    updated_by = EXCLUDED.updated_by,
    updated_at = CURRENT_TIMESTAMP;
