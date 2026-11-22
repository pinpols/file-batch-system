-- ADR-050：同一 Workflow + 业务日的活跃根运行上限改为定义级配置。
-- 默认 1 保持“一天一个活跃运行”的既有语义；调高后允许同日多个 CREATED/RUNNING 根运行。
-- 实现用“活跃槽位 + 唯一约束”而不是计数：准入事务是 REPEATABLE READ，计数看不到并发事务
-- 已提交的插入；唯一约束在任何隔离级别下都由数据库保证，且运行离开活跃状态后槽位自动释放。
-- 同一 trigger_request 的幂等仍由 uk_workflow_run_trigger_request 保证。

alter table batch.workflow_definition
    add column max_active_runs_per_biz_date integer not null default 1;

alter table batch.workflow_definition
    add constraint ck_workflow_definition_max_active_runs
        check (max_active_runs_per_biz_date between 1 and 64);

comment on column batch.workflow_definition.max_active_runs_per_biz_date is
    '同一租户、Workflow、业务日与执行模式允许同时存在的 CREATED/RUNNING 根运行上限；默认 1。';

alter table batch.workflow_run
    add column active_slot smallint not null default 1;

alter table batch.workflow_run
    add constraint ck_workflow_run_active_slot
        check (active_slot between 1 and 64);

comment on column batch.workflow_run.active_slot is
    '同租户/Workflow/业务日/模式下的活跃槽位（1..max_active_runs_per_biz_date）；终态后不再占用唯一约束。';

-- 归档冷表必须与热表列类型/空值约束一致，否则 ArchiveSchemaDriftCheck 启动失败。
alter table archive.workflow_run_archive
    add column active_slot smallint not null default 1;

drop index if exists batch.uk_workflow_run_active;

create unique index uk_workflow_run_active_slot
    on batch.workflow_run (tenant_id, workflow_definition_id, biz_date, dry_run, active_slot)
    where run_status in ('CREATED', 'RUNNING');

create index idx_workflow_run_active_lookup
    on batch.workflow_run (tenant_id, workflow_definition_id, biz_date, dry_run)
    where run_status in ('CREATED', 'RUNNING');
