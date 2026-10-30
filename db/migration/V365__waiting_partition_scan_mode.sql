-- 每轮先扫描正式分片，再扫描演练分片；重置旧轮次以避免切换时跳过低 ID 候选。
ALTER TABLE batch.waiting_partition_scan_cursor
    ADD COLUMN cycle_dry_run boolean NOT NULL DEFAULT false;
UPDATE batch.waiting_partition_scan_cursor
SET last_partition_id = 0, cycle_upper_id = 0, cycle_dry_run = false, version = version + 1;
COMMENT ON COLUMN batch.waiting_partition_scan_cursor.cycle_dry_run IS '当前轮次是否正在扫描演练分片；正式分片阶段为 false。';
