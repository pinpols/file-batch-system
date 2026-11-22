-- 当尚未观察到的未来触发重新启用时，保留其停用/替换处置记录。

CREATE TABLE batch.scheduled_occurrence_supersession_history (
    id                    BIGSERIAL PRIMARY KEY,
    tenant_id             VARCHAR(64)  NOT NULL,
    workflow_definition_id BIGINT       NOT NULL,
    scheduled_fire_time   TIMESTAMPTZ  NOT NULL,
    from_status           VARCHAR(32)  NOT NULL,
    to_status             VARCHAR(32)  NOT NULL,
    schedule_version      INTEGER      NOT NULL,
    calendar_code         VARCHAR(128),
    biz_date              DATE,
    request_id            VARCHAR(128),
    changed_at            TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_scheduled_occurrence_history_transition
        CHECK (from_status = 'SUPERSEDED' OR to_status = 'SUPERSEDED')
);

CREATE INDEX idx_scheduled_occurrence_supersession_history_identity
    ON batch.scheduled_occurrence_supersession_history
       (tenant_id, workflow_definition_id, scheduled_fire_time, changed_at);

COMMENT ON TABLE batch.scheduled_occurrence_supersession_history IS
    '记录计划变更后定时 occurrence 被替代或重新激活的不可变审计信息。';
COMMENT ON COLUMN batch.scheduled_occurrence_supersession_history.from_status IS
    '替代或重新激活状态转换前的 occurrence 状态。';
COMMENT ON COLUMN batch.scheduled_occurrence_supersession_history.to_status IS
    '替代或重新激活状态转换后的 occurrence 状态。';
COMMENT ON COLUMN batch.scheduled_occurrence_supersession_history.schedule_version IS
    '与该状态转换关联的 Workflow 调度版本。';

CREATE OR REPLACE FUNCTION batch.record_scheduled_occurrence_supersession()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = batch, pg_temp
AS $$
BEGIN
    IF OLD.occurrence_status IS DISTINCT FROM NEW.occurrence_status
       AND (OLD.occurrence_status = 'SUPERSEDED' OR NEW.occurrence_status = 'SUPERSEDED') THEN
        INSERT INTO batch.scheduled_occurrence_supersession_history (
            tenant_id,
            workflow_definition_id,
            scheduled_fire_time,
            from_status,
            to_status,
            schedule_version,
            calendar_code,
            biz_date,
            request_id
        ) VALUES (
            NEW.tenant_id,
            NEW.workflow_definition_id,
            NEW.scheduled_fire_time,
            OLD.occurrence_status,
            NEW.occurrence_status,
            NEW.schedule_version,
            NEW.calendar_code,
            NEW.biz_date,
            NEW.request_id
        );
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION batch.record_scheduled_occurrence_supersession() FROM PUBLIC;

CREATE TRIGGER trg_scheduled_occurrence_supersession_history
    AFTER UPDATE OF occurrence_status ON batch.scheduled_occurrence
    FOR EACH ROW
    EXECUTE FUNCTION batch.record_scheduled_occurrence_supersession();
