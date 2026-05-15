-- ============================================================================
-- V125: batch_day_replay_session.config_version_policy CHECK 补全
-- ----------------------------------------------------------------------------
-- 背景：V124 R3-P0 收紧 CHECK 时只允许 USE_ORIGINAL_CONFIG /
--      SNAPSHOT_JOB_DEFINITION_VERSION_ON_CREATE 两个值；
--      但 console RerunRequest 与 CompensationSubmitCommand 文档/校验注解明确支持：
--        USE_ORIGINAL_CONFIG
--        USE_LATEST_CONFIG
--        USE_SPECIFIED_VERSION
--      （SNAPSHOT_JOB_DEFINITION_VERSION_ON_CREATE 是 BatchDayReplay 路径专用别名）
--      compensation / rerun → BatchDayReplay 落 session 时一旦命中 USE_LATEST_CONFIG /
--      USE_SPECIFIED_VERSION 会被 V124 CHECK 直接拒掉。
--
-- 处置：DROP V124 旧 CHECK，重建为完整 4 值枚举 + 新增的 2 个 console 合法值。
-- R6 audit P0-5 落地。
-- ============================================================================

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_replay_session_config_version_policy') THEN
        ALTER TABLE batch.batch_day_replay_session
            DROP CONSTRAINT ck_replay_session_config_version_policy;
    END IF;

    ALTER TABLE batch.batch_day_replay_session
        ADD CONSTRAINT ck_replay_session_config_version_policy
        CHECK (config_version_policy IS NULL
               OR config_version_policy IN (
                   'USE_ORIGINAL_CONFIG',
                   'USE_LATEST_CONFIG',
                   'USE_SPECIFIED_VERSION',
                   'SNAPSHOT_JOB_DEFINITION_VERSION_ON_CREATE'))
        NOT VALID;
END $$;
