-- V215 - Console 多副本共享维护状态。

CREATE TABLE IF NOT EXISTS batch.console_maintenance_state (
    id                    SMALLINT PRIMARY KEY,
    enabled               BOOLEAN NOT NULL DEFAULT FALSE,
    read_only             BOOLEAN NOT NULL DEFAULT FALSE,
    message               VARCHAR(2048),
    eta_at                TIMESTAMPTZ,
    affected_services     JSONB NOT NULL DEFAULT '[]'::jsonb,
    version               BIGINT NOT NULL DEFAULT 0,
    updated_by            VARCHAR(64),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_console_maintenance_state_singleton CHECK (id = 1),
    CONSTRAINT ck_console_maintenance_state_services_array
        CHECK (jsonb_typeof(affected_services) = 'array'),
    CONSTRAINT ck_console_maintenance_state_version CHECK (version >= 0)
);

INSERT INTO batch.console_maintenance_state (id)
VALUES (1)
ON CONFLICT (id) DO NOTHING;

COMMENT ON TABLE batch.console_maintenance_state IS
    'Console 维护状态唯一事实源；所有 Console 副本从该单例读取并按 version 收敛。';
COMMENT ON COLUMN batch.console_maintenance_state.version IS
    '维护状态乐观锁版本；更新必须使用 version CAS。';
COMMENT ON COLUMN batch.console_maintenance_state.affected_services IS
    '受影响子系统 code JSON 数组；仅用于维护公告展示。';
