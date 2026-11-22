-- 定时触发投影和预期工作义务都保留来源
-- 请求标识。只要任一账本记录仍存在，就不能删除请求；
-- 终态账本的保留由显式清理协议负责，
-- 而不是级联删除父请求。

ALTER TABLE batch.expected_work_occurrence
    ADD CONSTRAINT fk_expected_work_trigger_request
        FOREIGN KEY (tenant_id, trigger_request_id)
        REFERENCES batch.trigger_request (tenant_id, request_id) NOT VALID;
ALTER TABLE batch.expected_work_occurrence
    VALIDATE CONSTRAINT fk_expected_work_trigger_request;

ALTER TABLE batch.scheduled_occurrence
    ADD CONSTRAINT fk_scheduled_occurrence_trigger_request
        FOREIGN KEY (tenant_id, request_id)
        REFERENCES batch.trigger_request (tenant_id, request_id) NOT VALID;
ALTER TABLE batch.scheduled_occurrence
    VALIDATE CONSTRAINT fk_scheduled_occurrence_trigger_request;

COMMENT ON CONSTRAINT fk_expected_work_trigger_request
    ON batch.expected_work_occurrence IS
    '租户范围内的来源请求；NO ACTION 可保留预期工作证据，直至执行显式生命周期清理。';
COMMENT ON CONSTRAINT fk_scheduled_occurrence_trigger_request
    ON batch.scheduled_occurrence IS
    '租户范围内的来源请求；NO ACTION 可保留定时触发来源记录，直至执行显式生命周期清理。';
