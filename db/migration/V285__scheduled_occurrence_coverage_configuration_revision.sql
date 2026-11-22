-- 记录计划 occurrence 覆盖投影所包含的租户配置修订号。

ALTER TABLE batch.scheduled_occurrence_coverage
    ADD COLUMN configuration_revision BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN batch.scheduled_occurrence_coverage.configuration_revision IS
    '此 occurrence 投影水位完整覆盖的租户配置修订号。';
