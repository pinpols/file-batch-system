-- 全新数据库部署会在应用流量或业务数据出现前执行迁移。
-- 以下直接创建已验证约束是有意设计；这不是在线升级。

ALTER TABLE batch.scheduled_occurrence
    -- squawk-ignore constraint-missing-not-valid, disallowed-unique-constraint
    ADD CONSTRAINT uk_scheduled_occurrence_request_identity
        UNIQUE (tenant_id, workflow_definition_id, scheduled_fire_time, biz_date, request_id);

ALTER TABLE batch.scheduled_occurrence_catch_up_audit
    -- squawk-ignore constraint-missing-not-valid
    ADD CONSTRAINT fk_scheduled_occurrence_catch_up_identity
        FOREIGN KEY (tenant_id, workflow_definition_id, scheduled_fire_time, biz_date, request_id)
        REFERENCES batch.scheduled_occurrence
            (tenant_id, workflow_definition_id, scheduled_fire_time, biz_date, request_id)
        ON UPDATE NO ACTION
        ON DELETE NO ACTION;
