-- Worker回报发件箱按执行尝试保留结果，并以领取凭据隔离迟到的确认与失败。
-- 仅用于ADR-048规定的全新数据库、停旧启新；不连接旧运行队列，不提供存量库升级。

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM batch.worker_report_outbox) THEN
        RAISE EXCEPTION 'V360 requires an empty worker report outbox; preserve the previous queue and deploy to a fresh database';
    END IF;
END;
$$;

ALTER TABLE batch.worker_report_outbox
    ADD COLUMN claim_token VARCHAR(64);

ALTER TABLE batch.worker_report_outbox
    ALTER COLUMN partition_invocation_id SET DEFAULT '',
    ALTER COLUMN partition_invocation_id SET NOT NULL,
    DROP CONSTRAINT uq_worker_report_outbox_tenant_task,
    ADD CONSTRAINT uq_worker_report_outbox_tenant_task_invocation
        UNIQUE (tenant_id, task_id, partition_invocation_id);

COMMENT ON COLUMN batch.worker_report_outbox.claim_token IS
    '当前投递领取凭据；重新入队、陈旧回收或完成本次尝试后失效';

COMMENT ON CONSTRAINT uq_worker_report_outbox_tenant_task_invocation
    ON batch.worker_report_outbox IS
    '同租户、同任务、同执行尝试去重；空执行身份使用空串，不覆盖其他尝试';
