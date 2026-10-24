-- 每个文件与渠道组合只对应一条可变派发记录。保护首次插入，避免并发 Worker 竞争，
-- 确保只有一个 Worker 能取得持久化的 CREATED 准入状态。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM batch.file_dispatch_record
        GROUP BY tenant_id, file_id, channel_code
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'duplicate file_dispatch_record identities exist; resolve them before applying V331';
    END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_file_dispatch_record_tenant_file_channel
    ON batch.file_dispatch_record (tenant_id, file_id, channel_code);
