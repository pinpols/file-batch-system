INSERT INTO biz.customer_account (
    tenant_id, customer_no, customer_name, customer_type, certificate_no, mobile_no,
    email, status, source_file_name, source_batch_no, source_trace_id, created_by, updated_by
) VALUES
    (:'tenant_id', 'EXP-BUNDLE-' || :'run_id' || '-1', 'Bundle Export 1', 'ENTERPRISE', 'BNDLEXP1',
     '13910000001', 'bundle-exp1@example.com', 'ACTIVE', 'bundle', :'batch_no', 'sim', 'sim', 'sim'),
    (:'tenant_id', 'EXP-BUNDLE-' || :'run_id' || '-2', 'Bundle Export 2', 'ENTERPRISE', 'BNDLEXP2',
     '13910000002', 'bundle-exp2@example.com', 'ACTIVE', 'bundle', :'batch_no', 'sim', 'sim', 'sim')
ON CONFLICT (tenant_id, customer_no) DO UPDATE
SET customer_name = excluded.customer_name, status = excluded.status, updated_by = excluded.updated_by;
