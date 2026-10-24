ALTER TABLE batch.expected_work_occurrence
    ADD COLUMN missing_input_alerted_at TIMESTAMPTZ;

COMMENT ON COLUMN batch.expected_work_occurrence.missing_input_alerted_at IS
    'Timestamp when the overdue EXPECTED occurrence first emitted its missing-input alert; does not resolve or expire the obligation';

CREATE INDEX idx_expected_work_due_unalerted
    ON batch.expected_work_occurrence (tenant_id, calendar_code, biz_date, due_at, id)
    WHERE occurrence_status = 'EXPECTED'
      AND due_at IS NOT NULL
      AND missing_input_alerted_at IS NULL;
