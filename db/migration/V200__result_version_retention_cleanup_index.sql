-- V200: result_version retention cleanup support.
-- 仅 ARCHIVED 记录可进入热表清理范围；EFFECTIVE/PENDING 记录不得清理。
CREATE INDEX IF NOT EXISTS idx_result_version_archived_cleanup
    ON batch.result_version (updated_at, id)
    WHERE status = 'ARCHIVED';

COMMENT ON INDEX batch.idx_result_version_archived_cleanup IS
    '支持分批清理 batch 热表中的 ARCHIVED result_version 记录；归档镜像表保留独立数据';
