-- V200: result_version retention cleanup support.
-- Only ARCHIVED rows are candidates for hot-table cleanup; EFFECTIVE/PENDING rows are never eligible.
CREATE INDEX IF NOT EXISTS idx_result_version_archived_cleanup
    ON batch.result_version (updated_at, id)
    WHERE status = 'ARCHIVED';

COMMENT ON INDEX batch.idx_result_version_archived_cleanup IS
    'Supports bounded cleanup of ARCHIVED result_version rows from the batch hot table; archive mirror is retained separately';
