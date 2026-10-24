-- 约束批量日等待启动记录引用同租户的有效触发请求。

ALTER TABLE batch.batch_day_waiting_launch
    ADD CONSTRAINT fk_batch_day_wait_launch_trigger_request
    FOREIGN KEY (tenant_id, request_id)
    REFERENCES batch.trigger_request (tenant_id, request_id)
    ON DELETE NO ACTION
    ON UPDATE NO ACTION
    NOT VALID;

ALTER TABLE batch.batch_day_waiting_launch
    VALIDATE CONSTRAINT fk_batch_day_wait_launch_trigger_request;
