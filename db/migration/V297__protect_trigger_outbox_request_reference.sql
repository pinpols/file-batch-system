-- V297：让事务性 Trigger Outbox 始终关联其来源请求。
-- 发布方在同一事务内写入两条记录，而保留清理
-- 会先排除热表和归档 Outbox 记录，再删除
-- 请求。NO ACTION 在数据库层明确约束这一生命周期。

ALTER TABLE batch.trigger_outbox_event
    ADD CONSTRAINT fk_trigger_outbox_event_request
        FOREIGN KEY (tenant_id, request_id)
        REFERENCES batch.trigger_request (tenant_id, request_id) NOT VALID;

ALTER TABLE batch.trigger_outbox_event
    VALIDATE CONSTRAINT fk_trigger_outbox_event_request;

COMMENT ON CONSTRAINT fk_trigger_outbox_event_request
    ON batch.trigger_outbox_event IS
    '租户范围内的来源 Trigger 请求；NO ACTION 可保留 Outbox 来源记录，直至执行显式保留期清理。';
