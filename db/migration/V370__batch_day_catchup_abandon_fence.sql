-- 未建档的原请求仅在唯一键围栏获胜后才能被明确放弃。
ALTER TABLE batch.batch_day_catchup_operation
    DROP CONSTRAINT ck_batch_day_catchup_status;

ALTER TABLE batch.batch_day_catchup_operation
    ADD CONSTRAINT ck_batch_day_catchup_status
    CHECK (operation_status IN ('PREPARED', 'RUNNING', 'COMPLETE', 'UNKNOWN', 'ABANDONED'));

COMMENT ON COLUMN batch.batch_day_catchup_operation.operation_status IS
    'PREPARED、RUNNING、COMPLETE、UNKNOWN 或未建档原请求已围栏的 ABANDONED。';
