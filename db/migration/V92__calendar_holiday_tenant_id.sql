-- V92 P0-1: calendar_holiday 补 tenant_id, 与 CLAUDE.md "多租隔离" 硬约束对齐
--
-- 之前 UNIQUE (calendar_id, biz_date) 靠 business_calendar.tenant_id 间接保证;
-- 跨表 JOIN 才能租户过滤, PG planner 走不到 (tenant_id, ...) 复合索引;
-- 未来 partition / 分库时还得先补列. 现在补齐直列形式.
--
-- 数据回填: 从 business_calendar 反查 tenant_id, 单 UPDATE; 离线表小 (节假日条目级 KB).

ALTER TABLE batch.calendar_holiday
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);

UPDATE batch.calendar_holiday h
   SET tenant_id = c.tenant_id
  FROM batch.business_calendar c
 WHERE h.calendar_id = c.id
   AND h.tenant_id IS NULL;

ALTER TABLE batch.calendar_holiday
    ALTER COLUMN tenant_id SET NOT NULL;

ALTER TABLE batch.calendar_holiday
    DROP CONSTRAINT IF EXISTS uk_calendar_holiday;

ALTER TABLE batch.calendar_holiday
    ADD CONSTRAINT uk_calendar_holiday UNIQUE (tenant_id, calendar_id, biz_date);

-- 复合索引让 SELECT WHERE tenant_id=? AND calendar_id=? 走 index-only scan
CREATE INDEX IF NOT EXISTS idx_calendar_holiday_tenant
    ON batch.calendar_holiday (tenant_id, calendar_id, biz_date);

COMMENT ON COLUMN batch.calendar_holiday.tenant_id IS
    'V92: 多租隔离硬约束直列 (与 business_calendar.tenant_id 同源, 简化 SELECT 路径)';
