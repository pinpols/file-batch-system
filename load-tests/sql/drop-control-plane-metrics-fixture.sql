-- ⚠ 破坏性操作：DROP SCHEMA ... CASCADE 将删除目标 schema 内的全部对象和数据。
-- 用途：清理由 test-control-plane-metrics.py 生成的 closure_metrics_<UUID> 临时 schema。
-- 执行前必须确认目标为独立测试数据库，且 fixture_schema 来自上述验证器；禁止用于共享库或生产库。
-- 恢复方式：重新运行 test-control-plane-metrics.py 以创建并载入临时 fixture。
DROP SCHEMA :"fixture_schema" CASCADE;
