-- Stage 2c stale row for PARTITION_REPLACE_COPY verification.

INSERT INTO biz.import_stage2c_customer (
    tenant_id, customer_no, customer_name, customer_type, certificate_no,
    mobile_no, email, status, source_file_name, source_batch_no, source_trace_id,
    created_by, updated_by
)
VALUES (
    'ta', 'S2CREPSTALE', 'Stage2c stale row', 'PERSONAL', 'S2CREPSTALECERT',
    '13900009111', 's2c-stale@x.io', 'ACTIVE', 'stage2c-stale.xml', :'batch_no',
    'stage2c-stale', 'sim-e2e', 'sim-e2e'
);
