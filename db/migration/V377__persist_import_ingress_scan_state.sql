-- 导入入口按存储范围维护跨实例分页周期；这些表是可重建的扫描协调状态，不是文件登记事实。
-- 作用域可覆盖多个租户，仅迁移账号和明确授权的 Import Worker 角色可以访问。
CREATE TABLE batch.import_ingress_scan_storage_lease (
    storage_id VARCHAR(64) PRIMARY KEY CHECK (storage_id ~ '^[0-9a-f]{64}$'),
    lease_owner UUID,
    lease_until TIMESTAMPTZ,
    fence BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_import_ingress_storage_lease CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CONSTRAINT ck_import_ingress_storage_fence CHECK (fence >= 0)
);

CREATE TABLE batch.import_ingress_scan_state (
    scope_id VARCHAR(64) PRIMARY KEY CHECK (scope_id ~ '^[0-9a-f]{64}$'),
    storage_id VARCHAR(64) NOT NULL REFERENCES batch.import_ingress_scan_storage_lease (storage_id),
    bucket_name TEXT NOT NULL,
    object_prefix TEXT NOT NULL,
    config_digest VARCHAR(64) CHECK (config_digest ~ '^[0-9a-f]{64}$'),
    phase VARCHAR(16) NOT NULL DEFAULT 'DISCOVER',
    cycle BIGINT NOT NULL DEFAULT 1,
    list_marker TEXT,
    after_object_id BIGINT NOT NULL DEFAULT 0,
    window_id BIGINT NOT NULL DEFAULT 0 CHECK (window_id >= 0),
    revisit_marker TEXT,
    revisit_budget BIGINT NOT NULL DEFAULT 1 CHECK (revisit_budget > 0),
    revisit_remaining BIGINT NOT NULL DEFAULT 1,
    next_lane VARCHAR(8) NOT NULL DEFAULT 'FORWARD' CHECK (next_lane IN ('FORWARD', 'REVISIT')),
    cleanup_required BOOLEAN NOT NULL DEFAULT FALSE,
    apply_after_id BIGINT NOT NULL DEFAULT 0 CHECK (apply_after_id >= 0),
    apply_upper_id BIGINT NOT NULL DEFAULT 0,
    fence BIGINT NOT NULL DEFAULT 0,
    lease_owner UUID,
    lease_until TIMESTAMPTZ,
    waiting_since TIMESTAMPTZ,
    waiting_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_import_ingress_scan_phase CHECK (phase IN ('DISCOVER', 'INDEX', 'APPLY', 'CLEAN', 'MAINTENANCE')),
    CONSTRAINT ck_import_ingress_scan_config CHECK (config_digest IS NOT NULL OR phase IN ('DISCOVER', 'MAINTENANCE')),
    CONSTRAINT ck_import_ingress_scan_counters CHECK (cycle > 0 AND after_object_id >= 0 AND fence >= 0),
    CONSTRAINT ck_import_ingress_revisit_budget CHECK (revisit_remaining > 0 AND revisit_remaining <= revisit_budget),
    CONSTRAINT ck_import_ingress_apply_cursor CHECK (apply_upper_id >= 0 AND apply_after_id <= apply_upper_id),
    CONSTRAINT ck_import_ingress_scan_lease CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CONSTRAINT ck_import_ingress_scan_wait CHECK ((waiting_since IS NULL) = (waiting_until IS NULL))
);

CREATE TABLE batch.import_ingress_scan_object (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scope_id VARCHAR(64) NOT NULL REFERENCES batch.import_ingress_scan_state (scope_id),
    object_digest VARCHAR(64) NOT NULL CHECK (object_digest ~ '^[0-9a-f]{64}$'),
    object_key TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    etag TEXT,
    last_modified TIMESTAMPTZ,
    first_observed_at TIMESTAMPTZ NOT NULL,
    seen_cycle BIGINT NOT NULL,
    seen_window BIGINT NOT NULL DEFAULT 0 CHECK (seen_window >= 0),
    is_manifest BOOLEAN NOT NULL,
    manifest_wait_reason VARCHAR(32),
    CONSTRAINT uk_import_ingress_scan_object UNIQUE (scope_id, object_digest),
    CONSTRAINT ck_import_ingress_scan_object_values CHECK (size_bytes >= 0 AND seen_cycle > 0)
);

-- 周期和游标共同限定当前批次；窗口索引用于清单发现和成员处理。
CREATE INDEX idx_import_ingress_scan_object_cycle
    ON batch.import_ingress_scan_object (scope_id, seen_cycle, id);
CREATE INDEX idx_import_ingress_scan_waiting
    ON batch.import_ingress_scan_state (storage_id, waiting_since, scope_id) WHERE waiting_since IS NOT NULL;
CREATE INDEX idx_import_ingress_scan_manifest_window
    ON batch.import_ingress_scan_object (scope_id, seen_window, id) WHERE is_manifest;
CREATE INDEX idx_import_ingress_scan_object_scope_id
    ON batch.import_ingress_scan_object (scope_id, id);

COMMENT ON TABLE batch.import_ingress_scan_storage_lease IS '同一物理对象存储桶内的扫描租约，协调重叠前缀。';
COMMENT ON COLUMN batch.import_ingress_scan_storage_lease.storage_id IS '物理存储位置、桶和部署命名空间的摘要，不包含凭据或前缀。';
COMMENT ON COLUMN batch.import_ingress_scan_storage_lease.lease_owner IS '当前存储范围扫描执行者标识。';
COMMENT ON COLUMN batch.import_ingress_scan_storage_lease.lease_until IS '存储范围扫描租约的数据库时间截止点。';
COMMENT ON COLUMN batch.import_ingress_scan_storage_lease.fence IS '存储范围扫描租约的单调递增围栏。';
COMMENT ON COLUMN batch.import_ingress_scan_storage_lease.updated_at IS '存储范围扫描租约最近更新时间。';
COMMENT ON TABLE batch.import_ingress_scan_state IS '导入入口按存储范围维护的分页周期、租约及围栏状态。';
COMMENT ON COLUMN batch.import_ingress_scan_state.scope_id IS '物理存储范围与扫描前缀组成的摘要，不包含凭据。';
COMMENT ON COLUMN batch.import_ingress_scan_state.storage_id IS '所属物理存储桶的扫描租约标识。';
COMMENT ON COLUMN batch.import_ingress_scan_state.config_digest IS '扫描语义配置的规范化摘要；维护切换后首次扫描重新绑定。';
COMMENT ON COLUMN batch.import_ingress_scan_state.bucket_name IS '扫描目标对象存储桶。';
COMMENT ON COLUMN batch.import_ingress_scan_state.object_prefix IS '扫描目标前缀，可能包含业务路径。';
COMMENT ON COLUMN batch.import_ingress_scan_state.phase IS '当前扫描周期阶段。';
COMMENT ON COLUMN batch.import_ingress_scan_state.cycle IS '当前完整扫描周期编号。';
COMMENT ON COLUMN batch.import_ingress_scan_state.list_marker IS '当前枚举阶段的对象存储续页标记。';
COMMENT ON COLUMN batch.import_ingress_scan_state.after_object_id IS '当前处理阶段已完成的对象记录游标。';
COMMENT ON COLUMN batch.import_ingress_scan_state.window_id IS '当前有界发现窗口编号。';
COMMENT ON COLUMN batch.import_ingress_scan_state.revisit_marker IS '从前缀起点回扫时的对象存储续页标记。';
COMMENT ON COLUMN batch.import_ingress_scan_state.revisit_budget IS '当前回扫轮次允许的最大页数。';
COMMENT ON COLUMN batch.import_ingress_scan_state.revisit_remaining IS '当前回扫轮次剩余的页数。';
COMMENT ON COLUMN batch.import_ingress_scan_state.next_lane IS '下一次发现使用前向扫描还是回扫。';
COMMENT ON COLUMN batch.import_ingress_scan_state.cleanup_required IS '是否有已完整枚举周期需要清理旧快照。';
COMMENT ON COLUMN batch.import_ingress_scan_state.apply_after_id IS '当前成员处理轮次已完成的记录游标。';
COMMENT ON COLUMN batch.import_ingress_scan_state.apply_upper_id IS '当前成员处理轮次开始时确定的记录上界。';
COMMENT ON COLUMN batch.import_ingress_scan_state.fence IS '扫描租约的单调递增围栏。';
COMMENT ON COLUMN batch.import_ingress_scan_state.lease_owner IS '当前扫描执行者标识。';
COMMENT ON COLUMN batch.import_ingress_scan_state.lease_until IS '当前扫描租约的数据库时间截止点。';
COMMENT ON COLUMN batch.import_ingress_scan_state.waiting_since IS '扫描范围进入共享存储等待队列的时间。';
COMMENT ON COLUMN batch.import_ingress_scan_state.waiting_until IS '等待请求失活的数据库时间截止点。';
COMMENT ON COLUMN batch.import_ingress_scan_state.updated_at IS '扫描状态最近更新时间。';
COMMENT ON TABLE batch.import_ingress_scan_object IS '导入入口扫描到的对象快照和稳定批次清单索引。';
COMMENT ON COLUMN batch.import_ingress_scan_object.id IS '扫描记录标识，供阶段内有序分页。';
COMMENT ON COLUMN batch.import_ingress_scan_object.scope_id IS '所属扫描存储范围摘要。';
COMMENT ON COLUMN batch.import_ingress_scan_object.object_digest IS '对象键摘要；写入时仍需核对原始对象键防止摘要冲突。';
COMMENT ON COLUMN batch.import_ingress_scan_object.object_key IS '对象存储键，可能包含租户与业务路径。';
COMMENT ON COLUMN batch.import_ingress_scan_object.size_bytes IS '最近一次观察到的对象字节数。';
COMMENT ON COLUMN batch.import_ingress_scan_object.etag IS '最近一次观察到的对象版本标识。';
COMMENT ON COLUMN batch.import_ingress_scan_object.last_modified IS '最近一次观察到的对象修改时间。';
COMMENT ON COLUMN batch.import_ingress_scan_object.first_observed_at IS '当前对象快照首次观察时间，供稳定窗口判断。';
COMMENT ON COLUMN batch.import_ingress_scan_object.seen_cycle IS '最近一次枚举时所属的完整扫描周期。';
COMMENT ON COLUMN batch.import_ingress_scan_object.seen_window IS '最近一次枚举时所属的有界发现窗口。';
COMMENT ON COLUMN batch.import_ingress_scan_object.is_manifest IS '对象键是否属于批次清单协议。';
COMMENT ON COLUMN batch.import_ingress_scan_object.manifest_wait_reason IS '成员等待权威清单的最近原因，用于原因变化时记录告警。';

ALTER TABLE batch.import_ingress_scan_storage_lease ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.import_ingress_scan_storage_lease FORCE ROW LEVEL SECURITY;
ALTER TABLE batch.import_ingress_scan_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.import_ingress_scan_state FORCE ROW LEVEL SECURITY;
ALTER TABLE batch.import_ingress_scan_object ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.import_ingress_scan_object FORCE ROW LEVEL SECURITY;
REVOKE ALL ON batch.import_ingress_scan_storage_lease, batch.import_ingress_scan_state, batch.import_ingress_scan_object FROM PUBLIC;
REVOKE ALL ON SEQUENCE batch.import_ingress_scan_object_id_seq FROM PUBLIC;

-- 迁移及平台管理账号可跨范围维护；受限 Import 账号由独立授权脚本显式添加策略。
DO $$
BEGIN
    EXECUTE format('CREATE POLICY import_ingress_scan_storage_owner ON batch.import_ingress_scan_storage_lease TO %I USING (true) WITH CHECK (true)', current_user);
    EXECUTE format('CREATE POLICY import_ingress_scan_state_owner ON batch.import_ingress_scan_state TO %I USING (true) WITH CHECK (true)', current_user);
    EXECUTE format('CREATE POLICY import_ingress_scan_object_owner ON batch.import_ingress_scan_object TO %I USING (true) WITH CHECK (true)', current_user);
END;
$$;
