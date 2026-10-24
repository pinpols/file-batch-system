-- 保存触发请求最初受理的启动载荷，供审批和恢复流程原样重用。

ALTER TABLE batch.trigger_request ADD COLUMN launch_payload JSONB;

COMMENT ON COLUMN batch.trigger_request.launch_payload IS
    '原始受理的 LaunchRequest；审批和恢复时复用此载荷，不重新计算日期、区间或配置版本。';
