-- 节假日引用的日历必须与节假日记录属于同一租户。

CREATE UNIQUE INDEX IF NOT EXISTS uk_business_calendar_tenant_id
    ON batch.business_calendar (tenant_id, id);

ALTER TABLE batch.business_calendar
    ADD CONSTRAINT uk_business_calendar_tenant_id
        UNIQUE USING INDEX uk_business_calendar_tenant_id;

ALTER TABLE batch.calendar_holiday
    DROP CONSTRAINT calendar_holiday_calendar_id_fkey,
    ADD CONSTRAINT fk_calendar_holiday_business_calendar_tenant
        FOREIGN KEY (tenant_id, calendar_id)
        REFERENCES batch.business_calendar (tenant_id, id)
        ON DELETE NO ACTION
        NOT VALID;

ALTER TABLE batch.calendar_holiday
    VALIDATE CONSTRAINT fk_calendar_holiday_business_calendar_tenant;

COMMENT ON CONSTRAINT fk_calendar_holiday_business_calendar_tenant
    ON batch.calendar_holiday IS
    '节假日只能引用同租户业务日历，并阻止日历在节假日记录保留期间被删除';
