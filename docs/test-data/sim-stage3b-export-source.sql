-- Stage 3b Export source data.
-- Required psql variable: batch_no

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
