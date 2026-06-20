-- =========================================================
-- V180 - 触发器依赖感知 fire 的 readiness defer 状态(ADR-043 §6.4 补全)
-- Notes:
-- 1) readiness_deferred_since:上游未就绪时不再 skip 丢批,改 defer——记录首次 defer 的
--    原始 scheduled fire 时刻。非空=正在等上游就绪;wheel 据此 (a) 算已等待时长是否超
--    readinessWindow,(b) 把 bizDate pin 到原始触发时刻防 recheck 期间漂移到下一业务日。
--    就绪并 fire / 超窗 give-up 后清回 NULL。默认 NULL = 无 defer,所有存量行为不变。
-- 2) 扩展 last_fire_status CHECK:
--      新增 WAITING_READINESS         —— 等上游就绪中(每 recheck-interval 重检一次)
--      新增 WAITING_READINESS_TIMEOUT —— 等待超 readinessWindow,放弃本 bizDate(已 ERROR+metric 告警)
--    同时补回 SKIPPED_BY_CALENDAR —— doFire 在节假日 SKIP 时本就写该状态,但 V67 的 CHECK
--    清单遗漏了它(潜在约束违例),本次一并纳入。
-- 3) trigger_runtime_state 无 archive.* 镜像(已核对),故无归档迁移配对。
-- =========================================================

ALTER TABLE batch.trigger_runtime_state
    ADD COLUMN IF NOT EXISTS readiness_deferred_since TIMESTAMPTZ;

COMMENT ON COLUMN batch.trigger_runtime_state.readiness_deferred_since IS
    'ADR-043: 依赖未就绪首次 defer 的原始 scheduled fire 时刻;非空=正在等上游就绪(算等待时长是否超 readinessWindow + pin bizDate 防漂移);就绪 fire / 超窗 give-up 后清 NULL';

ALTER TABLE batch.trigger_runtime_state
    DROP CONSTRAINT IF EXISTS ck_trigger_runtime_state_last_status;

-- NOT VALID:ADD 不扫全表/不阻塞写(只对新写入生效);随后 VALIDATE 用较轻的
-- SHARE UPDATE EXCLUSIVE 锁补扫存量(允许并发读写)。满足 squawk constraint-missing-not-valid。
ALTER TABLE batch.trigger_runtime_state
    ADD CONSTRAINT ck_trigger_runtime_state_last_status CHECK (
        last_fire_status IS NULL OR last_fire_status IN
        ('FIRED', 'FAILED', 'SKIPPED_DUPLICATE', 'MISFIRE_CATCH_UP', 'MISFIRE_SKIPPED',
         'MISFIRE_PENDING', 'SKIPPED_BY_CALENDAR', 'WAITING_READINESS', 'WAITING_READINESS_TIMEOUT')
    ) NOT VALID;

ALTER TABLE batch.trigger_runtime_state
    VALIDATE CONSTRAINT ck_trigger_runtime_state_last_status;

COMMENT ON COLUMN batch.trigger_runtime_state.last_fire_status IS
    'FIRED=正常 fire / FAILED=fire 抛异常 / SKIPPED_DUPLICATE=DB UNIQUE 兜住的重复 / MISFIRE_CATCH_UP=AUTO 补跑 / MISFIRE_SKIPPED=NONE 策略跳过 / MISFIRE_PENDING=MANUAL_APPROVAL 待审 / SKIPPED_BY_CALENDAR=节假日 SKIP / WAITING_READINESS=等上游就绪中 / WAITING_READINESS_TIMEOUT=等待超窗放弃本 bizDate';
