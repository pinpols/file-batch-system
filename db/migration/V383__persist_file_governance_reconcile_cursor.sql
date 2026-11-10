-- 对账页游标按物理存储和对账前缀独立维护；对象处理失败时不得推进。
CREATE TABLE batch.file_governance_reconcile_cursor (
    storage_id VARCHAR(64) NOT NULL CHECK (storage_id ~ '^[0-9a-f]{64}$'),
    object_prefix TEXT NOT NULL,
    next_marker TEXT,
    revisit_marker TEXT,
    revisit_budget BIGINT NOT NULL DEFAULT 1 CHECK (revisit_budget > 0),
    revisit_remaining BIGINT NOT NULL DEFAULT 1 CHECK (revisit_remaining > 0 AND revisit_remaining <= revisit_budget),
    next_lane VARCHAR(8) NOT NULL DEFAULT 'FORWARD' CHECK (next_lane IN ('FORWARD', 'REVISIT')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (storage_id, object_prefix)
);

COMMENT ON TABLE batch.file_governance_reconcile_cursor IS '对象治理对账共享的有界分页进度，不表达文件处理状态。';
COMMENT ON COLUMN batch.file_governance_reconcile_cursor.storage_id IS '物理存储、桶和部署命名空间的摘要。';
COMMENT ON COLUMN batch.file_governance_reconcile_cursor.object_prefix IS '本次对账使用的对象前缀，空字符串表示整个桶。';
COMMENT ON COLUMN batch.file_governance_reconcile_cursor.next_marker IS '下次对账页的起点；空值表示从该前缀的起点扫描。';
COMMENT ON COLUMN batch.file_governance_reconcile_cursor.revisit_marker IS '回访游标，用于发现前向游标之前后来出现的对象。';
COMMENT ON COLUMN batch.file_governance_reconcile_cursor.revisit_budget IS '本轮回访最多读取的页数。';
COMMENT ON COLUMN batch.file_governance_reconcile_cursor.revisit_remaining IS '本轮回访尚可读取的页数。';
COMMENT ON COLUMN batch.file_governance_reconcile_cursor.next_lane IS '下一次执行前向扫描或回访扫描。';
COMMENT ON COLUMN batch.file_governance_reconcile_cursor.version IS '分页进度乐观并发版本。';
COMMENT ON COLUMN batch.file_governance_reconcile_cursor.updated_at IS '分页进度最近更新时间。';
