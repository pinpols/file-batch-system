-- 将外部事件标识持久化到其启动请求，使回调不会
-- 在同一作业和业务日期下满足另一条事件预期。

ALTER TABLE batch.trigger_request
    ADD COLUMN external_event_code VARCHAR(128);

ALTER TABLE batch.trigger_request
    ADD CONSTRAINT ck_trigger_request_external_event_identity
    CHECK (
        external_event_code IS NULL
        OR (trigger_type = 'EVENT' AND length(btrim(external_event_code)) > 0)
    ) NOT VALID;

ALTER TABLE batch.trigger_request
    VALIDATE CONSTRAINT ck_trigger_request_external_event_identity;

COMMENT ON COLUMN batch.trigger_request.external_event_code IS
    'EVENT 请求的外部事件身份；其他启动来源及内部 EVENT 请求为空。';
