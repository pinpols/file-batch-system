-- ============================================================
-- V86: ck_job_execution_log_type CHECK 加 COMPENSATION_REJECTED
-- ============================================================
-- 背景：DefaultCompensationService.java:517 在 compensate 拒绝路径
-- (重复提交 / target 找不到 / 业务校验失败) 想往 job_execution_log
-- 写 audit 行,使用 log_type='COMPENSATION_REJECTED'。但 V40 的
-- ck_job_execution_log_type CHECK 只覆盖 6 值
-- ('SYSTEM','BUSINESS','RETRY','ALARM','AUDIT','COMPENSATION'),
-- 漏 'COMPENSATION_REJECTED' → 拒绝路径 INSERT 撞 CHECK 约束 →
-- BizException 之外又抛 SQLException → API 返回 500 而非合法 4xx。
--
-- 修法：扩 CHECK 把 COMPENSATION_REJECTED 加进白名单。
-- 顺手也把代码层用到但 CHECK 没的 'COMPENSATION_SUBMIT' / 'COMPENSATION_FAILED'
-- 等未来可能用到的子类也加上,避免每加一个就要新 migration。
-- ============================================================

ALTER TABLE batch.job_execution_log DROP CONSTRAINT IF EXISTS ck_job_execution_log_type;

ALTER TABLE batch.job_execution_log ADD CONSTRAINT ck_job_execution_log_type
    CHECK (log_type IN (
        'SYSTEM',
        'BUSINESS',
        'RETRY',
        'ALARM',
        'AUDIT',
        'COMPENSATION',
        'COMPENSATION_REJECTED',
        'COMPENSATION_SUBMIT',
        'COMPENSATION_FAILED'
    ));

COMMENT ON CONSTRAINT ck_job_execution_log_type ON batch.job_execution_log IS
    'V86 (2026-05-03) 扩展白名单: 加 COMPENSATION_REJECTED 修复 audit 写入路径; 顺带加 SUBMIT/FAILED 子类预留';
