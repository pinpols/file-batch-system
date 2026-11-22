-- 持久化原始日历日期扫描位置，避免滚动或阻塞日期卡住补扫。

CREATE TABLE IF NOT EXISTS batch.batch_day_open_cursor (
    tenant_id                VARCHAR(64)  NOT NULL,
    calendar_code            VARCHAR(128) NOT NULL,
    last_scanned_biz_date    DATE         NOT NULL,
    last_scan_at              TIMESTAMPTZ,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_batch_day_open_cursor PRIMARY KEY (tenant_id, calendar_code)
);

ALTER TABLE batch.batch_day_open_cursor ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.batch_day_open_cursor FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS batch_day_open_cursor_tenant_isolation
    ON batch.batch_day_open_cursor;
CREATE POLICY batch_day_open_cursor_tenant_isolation
    ON batch.batch_day_open_cursor
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

COMMENT ON TABLE batch.batch_day_open_cursor IS
    '每租户/日历的原始业务日扫描位置，和批量日创建同事务推进，支持停机恢复及节假日滚动续扫';
COMMENT ON COLUMN batch.batch_day_open_cursor.last_scanned_biz_date IS
    '已评估的最后一个原始业务日；与节假日滚动后的有效 biz_date 分开，避免滚动目标提前导致漏扫';
