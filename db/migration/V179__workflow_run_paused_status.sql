-- =========================================================
-- V179 - workflow_run 可逆暂停 PAUSED(ADR-044 Phase B)
-- Notes:
-- 1) 放宽 ck_workflow_run_status 接纳 PAUSED:RUNNING workflow 可暂停 DAG 推进
--    (停发下游节点,在途节点自然终结),resume 回 RUNNING。可逆非终态。
-- 2) NOT VALID + 后置 VALIDATE:ADD 不全表扫描/不长锁写(squawk);新约束是旧超集
--    (仅加 PAUSED),存量行必满足,VALIDATE 不失败。
-- 3) archive.workflow_run_archive 不更新:PAUSED 非终态永不归档,ArchiveSchemaDriftCheck
--    只比对 column(非 CHECK 约束),不会漂移。
-- =========================================================

ALTER TABLE batch.workflow_run DROP CONSTRAINT IF EXISTS ck_workflow_run_status;
ALTER TABLE batch.workflow_run ADD CONSTRAINT ck_workflow_run_status
    CHECK (run_status IN (
        'CREATED', 'RUNNING', 'PAUSED', 'SUCCESS', 'FAILED', 'TERMINATED',
        'SUCCESS_DRY_RUN', 'FAILED_DRY_RUN'
    )) NOT VALID;
ALTER TABLE batch.workflow_run VALIDATE CONSTRAINT ck_workflow_run_status;
