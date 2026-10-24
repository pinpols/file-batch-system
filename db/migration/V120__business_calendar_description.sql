-- 为业务日历增加供配置界面和配置包使用的说明字段。

ALTER TABLE batch.business_calendar
    ADD COLUMN IF NOT EXISTS description VARCHAR(512);

COMMENT ON COLUMN batch.business_calendar.description IS
    '业务日历说明，供 Console Excel 导入/导出和配置包导入使用。';
