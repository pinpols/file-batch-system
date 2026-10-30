-- 将单作业对应的 Workflow 定义 ID 快照到 Workflow 运行及归档记录。

ALTER TABLE batch.workflow_run
    ADD COLUMN IF NOT EXISTS single_job_definition_id BIGINT;

ALTER TABLE archive.workflow_run_archive
    ADD COLUMN IF NOT EXISTS single_job_definition_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'fk_workflow_run_single_job_definition'
           AND conrelid = 'batch.workflow_run'::regclass
    ) THEN
        ALTER TABLE batch.workflow_run
            ADD CONSTRAINT fk_workflow_run_single_job_definition
            FOREIGN KEY (tenant_id, single_job_definition_id)
            REFERENCES batch.job_definition (tenant_id, id);
    END IF;
END $$;

COMMENT ON COLUMN batch.workflow_run.single_job_definition_id IS
    '启动时冻结的单作业 Workflow 关联；组合 Workflow 和历史运行时为空。';
