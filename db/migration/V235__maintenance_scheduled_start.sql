alter table batch.console_maintenance_state
    add column starts_at timestamptz;

comment on column batch.console_maintenance_state.starts_at is
    'Planned maintenance start time; null means maintenance takes effect immediately when enabled.';
