-- 正式工作流与 dry-run 演练允许在同一业务日并存；每种模式仍仅允许一个活跃 run。
DROP INDEX IF EXISTS batch.uk_workflow_run_active;

CREATE UNIQUE INDEX uk_workflow_run_active
    ON batch.workflow_run (tenant_id, workflow_definition_id, biz_date, dry_run)
    WHERE run_status IN ('CREATED', 'RUNNING');

COMMENT ON INDEX batch.uk_workflow_run_active IS
    '同租户、工作流、业务日及执行模式仅允许一个 CREATED/RUNNING run；正式执行与 dry-run 相互隔离。';
