-- 仅隔离性能夹具：先保存无索引计划，再比较按 tenant/date 读取的访问路径。
CREATE INDEX wdi_test_source_tenant_date_idx
    ON biz.process_order_event (tenant_id, biz_date);
ANALYZE biz.process_order_event;
