-- 全局扫描位置由调度器共享；仅保存候选扫描进度，不表达租户或任务完成状态。
CREATE TABLE batch.waiting_partition_scan_cursor (
    singleton_id smallint PRIMARY KEY CHECK (singleton_id = 1),
    last_partition_id bigint NOT NULL DEFAULT 0 CHECK (last_partition_id >= 0),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO batch.waiting_partition_scan_cursor (singleton_id) VALUES (1);
COMMENT ON TABLE batch.waiting_partition_scan_cursor IS '等待分片全局扫描的共享轮转位置；不存放租户业务数据。';
COMMENT ON COLUMN batch.waiting_partition_scan_cursor.singleton_id IS '固定为 1 的单例键。';
COMMENT ON COLUMN batch.waiting_partition_scan_cursor.last_partition_id IS '上一批扫描末尾分片 ID；0 表示从头扫描。';
COMMENT ON COLUMN batch.waiting_partition_scan_cursor.version IS '扫描位置的乐观并发版本。';
COMMENT ON COLUMN batch.waiting_partition_scan_cursor.updated_at IS '扫描位置最近更新时刻。';
