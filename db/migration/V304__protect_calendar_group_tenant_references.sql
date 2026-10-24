-- 日历和 GROUP 假期只能引用同租户日历组；GROUP 假期的代表日历必须属于该组。

CREATE UNIQUE INDEX IF NOT EXISTS uk_business_calendar_tenant_group_id
    ON batch.business_calendar (tenant_id, group_code, id);

ALTER TABLE batch.business_calendar
    ADD CONSTRAINT uk_business_calendar_tenant_group_id
        UNIQUE USING INDEX uk_business_calendar_tenant_group_id;

COMMENT ON CONSTRAINT uk_business_calendar_tenant_group_id
    ON batch.business_calendar IS
    '供同租户日历组代表日历复合外键引用';

CREATE INDEX IF NOT EXISTS idx_business_calendar_tenant_group_reference
    ON batch.business_calendar (tenant_id, group_code)
    WHERE group_code IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_calendar_holiday_tenant_group_calendar_reference
    ON batch.calendar_holiday (tenant_id, group_code, calendar_id)
    WHERE group_code IS NOT NULL;

ALTER TABLE batch.business_calendar
    ADD CONSTRAINT fk_business_calendar_calendar_group_tenant
        FOREIGN KEY (tenant_id, group_code)
        REFERENCES batch.calendar_group (tenant_id, group_code)
        ON DELETE NO ACTION
        NOT VALID;

ALTER TABLE batch.calendar_holiday
    ADD CONSTRAINT fk_calendar_holiday_calendar_group_tenant
        FOREIGN KEY (tenant_id, group_code)
        REFERENCES batch.calendar_group (tenant_id, group_code)
        ON DELETE NO ACTION
        NOT VALID;

ALTER TABLE batch.calendar_holiday
    ADD CONSTRAINT fk_calendar_holiday_group_representative_calendar
        FOREIGN KEY (tenant_id, group_code, calendar_id)
        REFERENCES batch.business_calendar (tenant_id, group_code, id)
        ON DELETE NO ACTION
        NOT VALID;

ALTER TABLE batch.business_calendar
    VALIDATE CONSTRAINT fk_business_calendar_calendar_group_tenant;

ALTER TABLE batch.calendar_holiday
    VALIDATE CONSTRAINT fk_calendar_holiday_calendar_group_tenant;

ALTER TABLE batch.calendar_holiday
    VALIDATE CONSTRAINT fk_calendar_holiday_group_representative_calendar;

COMMENT ON CONSTRAINT fk_business_calendar_calendar_group_tenant
    ON batch.business_calendar IS
    '日历加入的分组必须存在且属于同一租户';

COMMENT ON CONSTRAINT fk_calendar_holiday_calendar_group_tenant
    ON batch.calendar_holiday IS
    'GROUP 假日引用的分组必须存在且属于同一租户';

COMMENT ON CONSTRAINT fk_calendar_holiday_group_representative_calendar
    ON batch.calendar_holiday IS
    'GROUP 假日代表日历必须属于同租户且加入该分组';
