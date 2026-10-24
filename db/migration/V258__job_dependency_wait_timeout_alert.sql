-- One-time alert claim; the wait stays pending and may still resume when upstreams become ready.
ALTER TABLE batch.job_dependency_waiting_launch
    ADD COLUMN timeout_alerted_at TIMESTAMPTZ;

COMMENT ON COLUMN batch.job_dependency_waiting_launch.timeout_alerted_at IS
    'CAS claim timestamp for the one-time timeout alert; does not change WAITING status';
