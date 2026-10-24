-- 为 Console 维护状态增加可选的计划开始时间。

alter table batch.console_maintenance_state
    add column starts_at timestamptz;

comment on column batch.console_maintenance_state.starts_at is
    '计划维护开始时间；NULL 表示启用维护时立即生效。';
