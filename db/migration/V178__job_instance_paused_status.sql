-- =========================================================
-- V178 - job_instance 可逆暂停态 PAUSED(ADR-044 Phase A)
-- Notes:
-- 1) 放宽 ck_job_instance_status 接纳 PAUSED:RUNNING 实例可暂停派发(停发新分区,
--    在途分区自然终结),resume 回 RUNNING。PAUSED 是可逆非终态。
-- 2) archive.job_instance_archive 不更新:PAUSED 非终态,永不进归档(归档只移终态/旧实例);
--    且 ArchiveSchemaDriftCheck 只比对 column 列表(非 CHECK 约束),不会因此漂移。
-- 3) workflow_run 的 PAUSED 留作 Phase B(同 PR 不混 workflow 暂停)。
-- =========================================================

-- NOT VALID + 后置 VALIDATE:ADD 不全表扫描/不长锁写(squawk constraint-missing-not-valid);
-- 新约束是旧约束的超集(仅加 PAUSED),存量行必然满足,VALIDATE 不会失败。
ALTER TABLE batch.job_instance DROP CONSTRAINT IF EXISTS ck_job_instance_status;
ALTER TABLE batch.job_instance ADD CONSTRAINT ck_job_instance_status
    CHECK (instance_status IN (
        'CREATED', 'WAITING', 'READY', 'RUNNING', 'PAUSED',
        'PARTIAL_FAILED', 'SUCCESS', 'FAILED', 'CANCELLED', 'TERMINATED',
        'SUCCESS_DRY_RUN', 'FAILED_DRY_RUN'
    )) NOT VALID;
ALTER TABLE batch.job_instance VALIDATE CONSTRAINT ck_job_instance_status;
