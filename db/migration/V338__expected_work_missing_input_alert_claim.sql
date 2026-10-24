-- 记录预期工作因输入缺失首次告警的时刻，并为待告警项提供扫描索引。

ALTER TABLE batch.expected_work_occurrence
    ADD COLUMN missing_input_alerted_at TIMESTAMPTZ;

COMMENT ON COLUMN batch.expected_work_occurrence.missing_input_alerted_at IS
    '逾期 EXPECTED occurrence 首次发出缺少输入告警的时刻；此字段不会解除或使该义务过期。';

CREATE INDEX idx_expected_work_due_unalerted
    ON batch.expected_work_occurrence (tenant_id, calendar_code, biz_date, due_at, id)
    WHERE occurrence_status = 'EXPECTED'
      AND due_at IS NOT NULL
      AND missing_input_alerted_at IS NULL;
