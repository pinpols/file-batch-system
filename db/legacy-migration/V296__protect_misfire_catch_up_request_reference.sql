-- 人工 misfire 保留其创建时对应的数字型 trigger_request id。
-- 审批完成。仅当待处理记录被明确
-- 按补扫生命周期完成处理并保留或归档。

ALTER TABLE batch.trigger_misfire_pending
    ADD CONSTRAINT fk_trigger_misfire_pending_catch_up_request
        FOREIGN KEY (tenant_id, catch_up_request_id)
        REFERENCES batch.trigger_request (tenant_id, id) NOT VALID;

ALTER TABLE batch.trigger_misfire_pending
    VALIDATE CONSTRAINT fk_trigger_misfire_pending_catch_up_request;

COMMENT ON CONSTRAINT fk_trigger_misfire_pending_catch_up_request
    ON batch.trigger_misfire_pending IS
    '租户范围内经批准的补跑请求；NO ACTION 可保留 misfire 来源记录，直至执行显式生命周期清理。';
