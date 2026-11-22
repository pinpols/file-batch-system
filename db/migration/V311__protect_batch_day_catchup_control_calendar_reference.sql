-- 约束批量日补跑控制记录引用同租户的业务日历。

ALTER TABLE batch.batch_day_catchup_control
    ADD CONSTRAINT fk_batch_day_catchup_control_business_calendar
    FOREIGN KEY (tenant_id, calendar_code)
    REFERENCES batch.business_calendar (tenant_id, calendar_code)
    ON DELETE NO ACTION
    ON UPDATE NO ACTION
    NOT VALID;

ALTER TABLE batch.batch_day_catchup_control
    VALIDATE CONSTRAINT fk_batch_day_catchup_control_business_calendar;
