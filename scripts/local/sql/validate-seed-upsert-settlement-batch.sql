INSERT INTO biz.settlement_batch (
    tenant_id,
    batch_no,
    biz_date,
    accounting_period,
    snapshot_mode,
    snapshot_ts,
    batch_status
)
VALUES (
    :'tenant_id',
    :'batch_no',
    CURRENT_DATE,
    to_char(CURRENT_DATE, 'YYYY-MM'),
    'BATCH',
    CURRENT_TIMESTAMP,
    'READY'
)
ON CONFLICT (tenant_id, batch_no) DO UPDATE
SET biz_date = EXCLUDED.biz_date,
    accounting_period = EXCLUDED.accounting_period,
    snapshot_mode = EXCLUDED.snapshot_mode,
    snapshot_ts = EXCLUDED.snapshot_ts,
    batch_status = EXCLUDED.batch_status;
