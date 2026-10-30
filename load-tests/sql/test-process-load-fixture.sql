-- 仅在独立空数据库验证 Process 压测日期夹具，不替代业务库迁移验收。
CREATE SCHEMA biz;
CREATE TABLE biz.process_order_event (
    tenant_id text NOT NULL,
    account_id text NOT NULL,
    biz_date date NOT NULL,
    event_id bigint NOT NULL,
    amount numeric(18, 2) NOT NULL,
    PRIMARY KEY (tenant_id, event_id)
);
