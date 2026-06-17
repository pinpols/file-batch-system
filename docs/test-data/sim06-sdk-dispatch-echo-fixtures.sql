-- sim06-sdk-dispatch-echo-fixtures.sql
-- sim 06 Phase 2(dispatch-execute 腿)的 job_definition fixture。
--
-- 目的:让平台真链路把一个任务派发给【自托管 SDK worker】并执行(真过 Kafka),作为 #544
-- workerType→handler 路由键反序列化 P0 的运行期对照(CI 守护 SdkWireContractTest 是单测对照)。
--
-- 设计:
--   - job_type='ATOMIC' —— ATOMIC 是唯一「单任务、无文件管线」的 base workerType;派单消息
--     workerType=job_type='ATOMIC' → 平台 resolve 出 topic batch.task.dispatch.atomic;
--     SDK worker 须有 taskType()="ATOMIC" 的 handler(sample 的 AtomicBaseEchoHandler)。
--   - worker_group='sdk-self-hosted' —— 与 SDK register 的 workerGroup 一致。平台自带
--     atomic-node-1 在组 ATOMIC,isWorkerClaimable 组门禁(partition.worker_group 非空时只有
--     同组 worker 能 CLAIM)→ 平台 worker 抢不到,只我方 SDK worker claim。确定性投递。
--   - 复用 default-tenant 既有 atomic 基础设施(atomic_queue / default-calendar / always_open),
--     fixture 只加这一行 job_definition,零额外依赖。
--   - 幂等:ON CONFLICT (id) DO NOTHING,可重复跑。
--
-- 前置:default-tenant 的 atomic demo 基础设施已 seed(scripts/db/test-seed/platform_seed.sql)。

INSERT INTO batch.job_definition (
    id, tenant_id, job_code, job_name, job_type, biz_type, schedule_type, schedule_expr, timezone, priority,
    queue_code, worker_group, calendar_code, window_code, trigger_mode, dag_enabled, shard_strategy, retry_policy,
    retry_max_count, timeout_seconds, execution_handler, param_schema, default_params, version, enabled, description,
    created_by, updated_by, created_at, updated_at
) VALUES
    (90061, 'default-tenant', 'SDK_VERIFY_DISPATCH_ECHO', 'SDK Verify Dispatch Echo', 'ATOMIC', 'GENERAL',
     'MANUAL', NULL, 'Asia/Shanghai', 5, 'atomic_queue', 'sdk-self-hosted', 'default-calendar', 'always_open',
     'MANUAL', FALSE, 'STATIC', 'EXPONENTIAL', 0, 300, NULL, jsonb_build_object('type', 'object'),
     jsonb_build_object('note', 'sim06 dispatch-execute leg', 'value', 'hello-from-sim06'),
     1, TRUE, 'sim06 自托管 SDK worker dispatch-execute 验证(ATOMIC 路由 → worker_group=sdk-self-hosted)',
     'system', 'system', TIMESTAMPTZ '2026-06-17 08:00:00+08', TIMESTAMPTZ '2026-06-17 08:00:00+08')
ON CONFLICT (id) DO NOTHING;
