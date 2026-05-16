-- ============================================================================
-- V127: VALIDATE 全部 V124/V125/V126 加的 NOT VALID 约束 + FK
-- ----------------------------------------------------------------------------
-- 背景：V124/V125/V126 加 CHECK / FK 时统一用 NOT VALID（避免历史脏数据阻塞
-- migration），原计划由 DBA 在运维窗口逐张表 VALIDATE。实际操作中常被遗忘，
-- 让 DB 长期处于"约束已加但未校验存量"的 drift 状态，新插入行能违反约束直到
-- VALIDATE 跑完。
--
-- 处置：本 migration 一次性 VALIDATE 所有 7 处 NOT VALID 约束。同启动期
-- 自检（NotValidConstraintGuard）联动，未来再加 NOT VALID 必须同一 sprint
-- 内补 VALIDATE migration。
--
-- 风险：VALIDATE 会全表扫并 SHARE UPDATE EXCLUSIVE 锁（PG ≥ 9.4），写阻塞但
-- 读不阻塞。低峰跑；大表（>1000w 行）需要分批 / 灰度。
--
-- 失败处置：某条 VALIDATE 抛 ERROR 表示存量违反约束 → migration 整体回滚，
-- 由 DBA 用 ad-hoc SQL 清理违反行后重跑（见 V124 上线指南 §1）。
-- ============================================================================

-- V124 加的 6 处
ALTER TABLE batch.batch_day_replay_session
    VALIDATE CONSTRAINT ck_replay_session_result_policy;

ALTER TABLE batch.result_version
    VALIDATE CONSTRAINT ck_result_version_dq_gate_status;

ALTER TABLE archive.result_version_archive
    VALIDATE CONSTRAINT ck_result_version_archive_dq_gate_status;

ALTER TABLE batch.data_quality_check
    VALIDATE CONSTRAINT fk_dq_check_rule_id;

ALTER TABLE batch.calendar_holiday
    VALIDATE CONSTRAINT ck_calendar_holiday_group_code_required;

-- V125 重建的 config_version_policy CHECK
ALTER TABLE batch.batch_day_replay_session
    VALIDATE CONSTRAINT ck_replay_session_config_version_policy;

-- V126 加的 worker_report_outbox.publish_status CHECK
ALTER TABLE batch.worker_report_outbox
    VALIDATE CONSTRAINT ck_worker_report_outbox_publish_status;
