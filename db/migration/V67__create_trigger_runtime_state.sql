-- =========================================================
-- V67: trigger_runtime_state 表 — 时间轮替换 Quartz 的状态持久化层
--
-- 现状(切换前):
--   Quartz 在 quartz.QRTZ_TRIGGERS 维护 NEXT_FIRE_TIME / TRIGGER_STATE
--
-- 切换后:
--   时间轮 leader 读 batch.trigger_runtime_state.next_fire_time
--   不再依赖 QRTZ_*(切换完成后由阶段 2 清理脚本 drop quartz schema)
--
-- 关联文档: docs/architecture/quartz-replacement-design.md §2.2
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.trigger_runtime_state (
    id                       BIGSERIAL    PRIMARY KEY,
    job_definition_id        BIGINT       NOT NULL REFERENCES batch.job_definition(id) ON DELETE CASCADE,
    tenant_id                VARCHAR(64)  NOT NULL,
    job_code                 VARCHAR(128) NOT NULL,
    -- 下次 fire 时刻(Quartz CronExpression.getNextValidTimeAfter 算)
    next_fire_time           TIMESTAMPTZ  NOT NULL,
    -- 上次 fire 实际时刻(可能因 misfire / leader 切换有延迟)
    last_fire_time           TIMESTAMPTZ,
    last_fire_status         VARCHAR(32),
    -- 调度占位 marker:某 leader 把这一条推进 wheel 时写自己的 instance_id;
    -- fire 完毕清回 NULL。其他 leader / 同 leader 下一周期扫库时跳过 marker IS NOT NULL 的行,
    -- 实现"滑动窗口去重"防 design.md §4 风险 R-2。
    scheduled_fire_marker    VARCHAR(128),
    scheduled_at             TIMESTAMPTZ,
    -- misfire 累计计数(用于运维观察某 trigger 长期不健康)
    misfire_count            BIGINT       NOT NULL DEFAULT 0,
    version                  INTEGER      NOT NULL DEFAULT 1,  -- 乐观锁
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_trigger_runtime_state_job_def UNIQUE (job_definition_id),
    CONSTRAINT ck_trigger_runtime_state_last_status CHECK (
        last_fire_status IS NULL OR last_fire_status IN
        ('FIRED', 'FAILED', 'SKIPPED_DUPLICATE', 'MISFIRE_CATCH_UP', 'MISFIRE_SKIPPED', 'MISFIRE_PENDING')
    )
);

-- 给 slidingWindow 扫库用:只看未占位的、即将 fire 的行
CREATE INDEX IF NOT EXISTS idx_trigger_runtime_state_next_fire
    ON batch.trigger_runtime_state (next_fire_time)
    WHERE scheduled_fire_marker IS NULL;

-- 给 stale marker 接管扫描用:周期清理崩溃 leader 留下的占位
CREATE INDEX IF NOT EXISTS idx_trigger_runtime_state_marker_stale
    ON batch.trigger_runtime_state (scheduled_at)
    WHERE scheduled_fire_marker IS NOT NULL;

-- 给运维查询某 tenant 所有 trigger 状态用
CREATE INDEX IF NOT EXISTS idx_trigger_runtime_state_tenant_job
    ON batch.trigger_runtime_state (tenant_id, job_code);

COMMENT ON TABLE batch.trigger_runtime_state IS
    '时间轮 trigger 状态持久化;替换 Quartz QRTZ_TRIGGERS 的运行时状态。详见 docs/architecture/quartz-replacement-design.md §2';
COMMENT ON COLUMN batch.trigger_runtime_state.scheduled_fire_marker IS
    'leader 调度占位:非 NULL 表示已被某 leader 推进 wheel,其他 leader 跳过。崩溃时 stale 接管阈值 5 min(详见 design §4.3)';
COMMENT ON COLUMN batch.trigger_runtime_state.last_fire_status IS
    'FIRED=正常 fire / FAILED=fire 抛异常 / SKIPPED_DUPLICATE=DB UNIQUE 兜住的重复 / MISFIRE_CATCH_UP=AUTO 补跑 / MISFIRE_SKIPPED=NONE 策略跳过 / MISFIRE_PENDING=MANUAL_APPROVAL 待审';
