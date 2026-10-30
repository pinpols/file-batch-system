-- 固定每个轮次的扫描上界，防止持续新增分片时无法回绕。
ALTER TABLE batch.waiting_partition_scan_cursor
    ADD COLUMN cycle_upper_id bigint NOT NULL DEFAULT 0;
UPDATE batch.waiting_partition_scan_cursor SET cycle_upper_id = last_partition_id;
ALTER TABLE batch.waiting_partition_scan_cursor
    ADD CONSTRAINT ck_waiting_scan_cycle_bounds CHECK (cycle_upper_id >= last_partition_id);
COMMENT ON COLUMN batch.waiting_partition_scan_cursor.cycle_upper_id IS '当前扫描轮次的分片 ID 上界；0 表示开始新轮次。';
