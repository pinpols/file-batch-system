-- =====================================================================
-- V201: 同步 dry-run 终态到冷归档表状态约束
-- =====================================================================
-- V117 扩展了热表 job_instance/workflow_run 的 dry-run 终态，但 V71 通过
-- LIKE INCLUDING CONSTRAINTS 创建的冷表仍保留旧 CHECK。归档 dry-run 记录时会
-- 违反约束并回滚整批事务。冷表约束应完整接受热表的合法状态集合。

ALTER TABLE archive.job_instance_archive
    DROP CONSTRAINT IF EXISTS ck_job_instance_status;
ALTER TABLE archive.job_instance_archive
    ADD CONSTRAINT ck_job_instance_status CHECK (instance_status IN (
        'CREATED', 'WAITING', 'READY', 'RUNNING', 'PAUSED',
        'PARTIAL_FAILED', 'SUCCESS', 'FAILED', 'CANCELLED', 'TERMINATED',
        'SUCCESS_DRY_RUN', 'FAILED_DRY_RUN'
    )) NOT VALID;
ALTER TABLE archive.job_instance_archive
    VALIDATE CONSTRAINT ck_job_instance_status;

ALTER TABLE archive.workflow_run_archive
    DROP CONSTRAINT IF EXISTS ck_workflow_run_status;
ALTER TABLE archive.workflow_run_archive
    ADD CONSTRAINT ck_workflow_run_status CHECK (run_status IN (
        'CREATED', 'RUNNING', 'PAUSED', 'SUCCESS', 'FAILED', 'TERMINATED',
        'SUCCESS_DRY_RUN', 'FAILED_DRY_RUN'
    )) NOT VALID;
ALTER TABLE archive.workflow_run_archive
    VALIDATE CONSTRAINT ck_workflow_run_status;
