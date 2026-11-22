-- event_delivery_log 和 event_outbox_retry 均早于 Outbox 分区化，且不含 outbox_event.created_at。
-- 补上分区键，使 PostgreSQL 能够
-- 通过原生复合外键强制校验完整的租户级父记录标识。
-- 此迁移属于全新数据库部署契约，执行时机早于
-- 应用流量或业务种子数据写入。
-- PostgreSQL 17 会为每个父表分区创建外键副本；仅验证
-- 分区父表会导致这些副本约束仍未验证，
-- 启动时的 schema 守卫会正确拒绝这种状态。因此直接创建已验证的外键。

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM batch.outbox_event
         GROUP BY tenant_id, id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Outbox tenant/id identity is ambiguous across partitions'
            USING ERRCODE = '23505',
                  CONSTRAINT = 'uk_outbox_event_tenant_id_created_at';
    END IF;
END;
$$;

ALTER TABLE batch.outbox_event
    -- squawk-ignore constraint-missing-not-valid, disallowed-unique-constraint
    ADD CONSTRAINT uk_outbox_event_tenant_id_created_at
        UNIQUE (tenant_id, id, created_at);

ALTER TABLE batch.event_delivery_log
    ADD COLUMN outbox_event_created_at TIMESTAMPTZ;
ALTER TABLE batch.event_outbox_retry
    ADD COLUMN outbox_event_created_at TIMESTAMPTZ;

UPDATE batch.event_delivery_log child
   SET outbox_event_created_at = parent.created_at
  FROM batch.outbox_event parent
 WHERE parent.tenant_id = child.tenant_id
   AND parent.id = child.outbox_event_id;

UPDATE batch.event_outbox_retry child
   SET outbox_event_created_at = parent.created_at
  FROM batch.outbox_event parent
 WHERE parent.tenant_id = child.tenant_id
   AND parent.id = child.outbox_event_id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM batch.event_delivery_log WHERE outbox_event_created_at IS NULL
    ) OR EXISTS (
        SELECT 1 FROM batch.event_outbox_retry WHERE outbox_event_created_at IS NULL
    ) THEN
        RAISE EXCEPTION 'Outbox child rows contain missing or cross-tenant hot parent references'
            USING ERRCODE = '23503',
                  CONSTRAINT = 'fk_outbox_child_event_reference';
    END IF;
END;
$$;

ALTER TABLE batch.event_delivery_log
    -- squawk-ignore adding-not-nullable-field
    ALTER COLUMN outbox_event_created_at SET NOT NULL;
ALTER TABLE batch.event_outbox_retry
    -- squawk-ignore adding-not-nullable-field
    ALTER COLUMN outbox_event_created_at SET NOT NULL;

CREATE INDEX idx_event_delivery_log_outbox_reference
    ON batch.event_delivery_log (tenant_id, outbox_event_id, outbox_event_created_at);
CREATE INDEX idx_event_outbox_retry_outbox_reference
    ON batch.event_outbox_retry (tenant_id, outbox_event_id, outbox_event_created_at);

ALTER TABLE batch.event_delivery_log
    -- squawk-ignore constraint-missing-not-valid
    ADD CONSTRAINT fk_event_delivery_log_outbox_event_reference
        FOREIGN KEY (tenant_id, outbox_event_id, outbox_event_created_at)
        REFERENCES batch.outbox_event (tenant_id, id, created_at)
        ON DELETE NO ACTION;
ALTER TABLE batch.event_outbox_retry
    -- squawk-ignore constraint-missing-not-valid
    ADD CONSTRAINT fk_event_outbox_retry_outbox_event_reference
        FOREIGN KEY (tenant_id, outbox_event_id, outbox_event_created_at)
        REFERENCES batch.outbox_event (tenant_id, id, created_at)
        ON DELETE NO ACTION;

-- 归档子记录时，保留冷审计记录的完整来源标识。

ALTER TABLE archive.event_delivery_log_archive
    ADD COLUMN outbox_event_created_at TIMESTAMPTZ;
ALTER TABLE archive.event_outbox_retry_archive
    ADD COLUMN outbox_event_created_at TIMESTAMPTZ;

UPDATE archive.event_delivery_log_archive child
   SET outbox_event_created_at = parent.created_at
  FROM archive.outbox_event_archive parent
 WHERE parent.tenant_id = child.tenant_id
   AND parent.id = child.outbox_event_id;

UPDATE archive.event_outbox_retry_archive child
   SET outbox_event_created_at = parent.created_at
  FROM archive.outbox_event_archive parent
 WHERE parent.tenant_id = child.tenant_id
   AND parent.id = child.outbox_event_id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM archive.event_delivery_log_archive WHERE outbox_event_created_at IS NULL
    ) OR EXISTS (
        SELECT 1 FROM archive.event_outbox_retry_archive WHERE outbox_event_created_at IS NULL
    ) THEN
        RAISE EXCEPTION 'Outbox archive child rows contain missing or cross-tenant archive parents'
            USING ERRCODE = '23503',
                  CONSTRAINT = 'fk_outbox_archive_child_event_reference';
    END IF;
END;
$$;

ALTER TABLE archive.event_delivery_log_archive
    -- squawk-ignore adding-not-nullable-field
    ALTER COLUMN outbox_event_created_at SET NOT NULL;
ALTER TABLE archive.event_outbox_retry_archive
    -- squawk-ignore adding-not-nullable-field
    ALTER COLUMN outbox_event_created_at SET NOT NULL;

COMMENT ON COLUMN batch.event_delivery_log.outbox_event_created_at IS
    '被引用 Outbox 事件的分区身份；属于 fk_event_delivery_log_outbox_event_reference 的组成部分。';
COMMENT ON COLUMN batch.event_outbox_retry.outbox_event_created_at IS
    '被引用 Outbox 事件的分区身份；属于 fk_event_outbox_retry_outbox_event_reference 的组成部分。';
