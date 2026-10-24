INSERT INTO biz.customer_account (
    tenant_id, customer_no, customer_name, customer_type, certificate_no,
    mobile_no, email, status, source_file_name, source_batch_no, source_trace_id,
    created_by, updated_by
)
SELECT 'ta', v.customer_no, v.customer_name, 'PERSONAL', v.certificate_no,
       v.mobile_no, v.email, v.status, 'stage3-export-seed.csv', :'batch_no',
       'stage3-export-seed', 'sim-e2e', 'sim-e2e'
FROM (VALUES
    ('EXP-000001', 'Export Stage3 A', 'EXPCERT000001', '13910000001', 'exp1@sim.io', 'ACTIVE'),
    ('EXP-000002', 'Export Stage3 B', 'EXPCERT000002', '13910000002', 'exp2@sim.io', 'ACTIVE'),
    ('EXP-000003', 'Export Stage3 C', 'EXPCERT000003', '13910000003', 'exp3@sim.io', 'INACTIVE'),
    ('EXP-000004', 'Export Stage3 D', 'EXPCERT000004', '13910000004', 'exp4@sim.io', 'ACTIVE')
) AS v(customer_no, customer_name, certificate_no, mobile_no, email, status)
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
