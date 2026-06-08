# 上线前 Worker P0 压测复验报告 - 2026-06-08

## 结论

P0 已按本地真实系统链路补跑并形成可复跑入口：

- 基线代码已提交固化到当前分支，后续压测不再混入未提交的 worker 业务矩阵修复。
- process / dispatch / atomic / trigger mixed 压力复验通过：全终态、Kafka lag 归零、无 `CREATED + NO_TASK`。
- atomic 1w task storm 通过：10000 个 launch 请求 0 KO，10000 个实例全终态；当前策略是 fail-close，不是排队全成功。
- trigger misfire / cron / outbox 专项通过：覆盖 cron 首发、暂停恢复、misfire replay、requestId dedup、outbox retry 恢复和 storm 全终态。
- PG 写入参数矩阵已完成 5 组 x 3 次取中位数；生产默认不建议关闭 `synchronous_commit`。

P1/P2 不能诚实标记为全部完成：真实云 S3/OSS、真实 SFTP/NAS 故障、worker kill / PG 断链恢复、10w storm 和多租户公平性容量画像仍需独立窗口。

## 基线提交

| Commit | 内容 |
|---|---|
| `5eb564b5e` | SQL / 配置从 shell 脚本拆出 |
| `fb3ed3746` | worker 业务场景矩阵补齐 |
| `c22ea859c` | 修复 process benchmark account_id 过长 fixture |

## P0-1 Mixed 压力复验

- RUN_ID：`preprod-p0-mixed-20260608140208`
- 报告：`load-tests/target/control-plane-worker-report-preprod-p0-mixed-20260608140208.md`
- Gatling：1080 请求，OK=1080，KO=0，p95=150ms，p99=1050ms
- 实例：720/720 全终态
- 终态分布：
  - `atomic_sql_demo`：192 SUCCESS，168 FAILED
  - `lt_dispatch_local_job`：95 SUCCESS，85 FAILED
  - `lt_process_sql_job`：95 SUCCESS，85 FAILED
- 验收：
  - `non_terminal=0`
  - `CREATED + NO_TASK=0`
  - Kafka lag snapshot after 全部为 0
  - 未检出 `orch likely unreachable` / `renew skipped: circuit OPEN` / `renew circuit OPENED`
- 解释：FAILED 均为 launch 阶段 `failure_class=BUSINESS_RULE`，没有 task，属于当前本机配额/容量 fail-close，不是 worker 执行后丢终态。

## P0-2 Atomic 1w Storm

- RUN_ID：`preprod-p0-atomic1w-20260608140503`
- 报告：`load-tests/target/control-plane-worker-report-preprod-p0-atomic1w-20260608140503.md`
- Gatling：10000 请求，OK=10000，KO=0，p95=26ms，p99=61ms，50 req/s
- 实例：10000/10000 全终态
- 终态分布：
  - SUCCESS：6522
  - FAILED：3478，`failure_class=BUSINESS_RULE`
- 验收：
  - `non_terminal=0`
  - `CREATED + NO_TASK=0`
  - Kafka lag snapshot after 全部为 0
  - 未检出 `orch likely unreachable` / `renew skipped: circuit OPEN` / `renew circuit OPENED`
- 解释：这证明 1w storm 在当前本机配额策略下可全终态；它不是“1w 全成功”。若上线目标要求全成功，需要单独调大 atomic 配额、worker 并发和 orchestrator backpressure 后重跑。

## P0-3 Trigger Misfire / Cron / Outbox 专项

- 脚本：`scripts/sim/24-trigger-stage6d.sh`
- SQL fixture：
  - `docs/test-data/sim-stage6d-trigger-fixtures.sql`
  - `docs/test-data/sim-stage6d-trigger-pause.sql`
  - `docs/test-data/sim-stage6d-trigger-resume.sql`
  - `docs/test-data/sim-stage6d-trigger-outbox-retry.sql`
- 成功批次：`sim-trigger-stage6d-20260608141724`
- 日志：`load-tests/target/trigger-stage6d-20260608141724/trigger-stage6d.log`
- 断言摘要：
  - `cron_before=1`
  - `pause=1->1`
  - `resume=2`
  - `misfire=2`
  - `replay=LAUNCHED|30155`
  - `dedup=1/1`
  - `outbox=12/12:instances=12`
  - `storm_terminal=80/80`
  - `pending_outbox=0`
  - `non_terminal=0`

限制说明：当前本地 trigger 为 wheel 模式，`pause/resume` 管理接口是 noop；本脚本用 `job_definition.enabled=false/true + trigger_runtime_state` 模拟停排恢复。亚分钟 `0/2` cron 在当前 wheel 参数下不能作为连续高频 fire 能力放行，只记录为“至少首发 + 恢复后可再发”；真正高频容量用 API storm 覆盖。

## P0-4 PG 写入参数矩阵

- 脚本：`scripts/local/pg-write-parameter-matrix.sh`
- RUN_ID：`pg-param-matrix-20260608142440`
- 报告：`load-tests/target/pg-param-matrix-20260608142440/pg-write-parameter-matrix.md`
- 规模：每组 100000 行，`BATCH_SIZE=5000`，每组 3 次取中位数
- 覆盖：
  - `checkpoint_timeout`
  - `max_wal_size`
  - `synchronous_commit`
  - `work_mem`
  - `maintenance_work_mem`
  - 目标表额外索引数量

| Case | batch s | copy s | direct s | direct rows/s | WAL bytes |
|---|---:|---:|---:|---:|---:|
| baseline | 3.630 | 4.193 | 2.605 | 38384 | 303239536 |
| workmem_64_maint_256 | 3.078 | 2.801 | 1.644 | 60828 | 303320016 |
| sync_commit_off | 3.395 | 2.778 | 2.000 | 50010 | 303320384 |
| wal_checkpoint_relaxed | 3.616 | 3.029 | 1.626 | 61504 | 303408504 |
| extra_indexes_3 | 4.203 | 3.851 | 2.629 | 38042 | 400276600 |

建议：

- 生产默认保留 `synchronous_commit=on`；`off` 只允许 benchmark / 单任务临时验证，因为改变崩溃语义。
- `work_mem=64MB`、`maintenance_work_mem=256MB` 在本地微基准收益明确，适合作为 worker 大批量导入/PROCESS 的 session scoped 候选值继续做系统级大数据复验。
- `checkpoint_timeout=15min`、`max_wal_size=8GB` 在 10w 微基准下收益不稳定，不能仅凭这轮下生产结论；需要 1000w/更长压测再看 checkpoint/WAL 压力。
- 额外 3 个二级索引使 WAL 中位数从约 303MB 增到约 400MB，写入耗时也变差；大批量导入应尽量减少目标分区二级索引，必要时后建。
- 矩阵结束后已恢复 PG 参数：`checkpoint_timeout=5min`、`max_wal_size=1GB`。

## P1 本地模拟复跑

| 项 | 批次 / 脚本 | 结果 | 边界 |
|---|---|---|---|
| dispatch 500 no-retry 补偿 | `scripts/sim/14-dispatch-stage5b.sh`，`sim-dispatch-stage5b-20260608143154` | PASS，`FAILED|FAILED|FAILED|COMPENSATED` | 覆盖本地 API 500/no-retry，不等于真实下游长时间 timeout/断连。 |
| dispatch LOCAL/NAS/SFTP manifest | `scripts/sim/20-dispatch-stage5c.sh`，`sim-dispatch-stage5c-20260608143209` | PASS，LOCAL/NAS/SFTP 均 `ACKED:SUCCESS`，SFTP `.chk` 存在 | NAS 是 local profile stub；真实 NAS 权限/断链仍未覆盖。 |
| atomic HTTP / SQL timeout / shell cancel | `scripts/sim/21-atomic-stage5c.sh`，`atomic-stage5c-20260608163608` | PASS，HTTP SUCCESS，SQL `TIMEOUT/TIMEOUT`，shell cancel HTTP 200 后 `FAILED/WORKER_EXECUTION_CANCELLED` | 取消/超时/重试组合矩阵仍待扩展。 |
| export 8 分片 / 三租户 / 幂等重放 | `scripts/sim/18-export-stage3c.sh`，`sim-export-stage3c-20260608143456` | PASS，ta 8/8 分片，三租户 3/3 SUCCESS，dedup 1/1 | 本地 MinIO/S3-compatible，不等于真实云 S3/OSS multipart abort/retry。 |
| process 分片 / RUNNING cancel | `scripts/sim/19-process-stage4c.sh`，`process-stage4c-20260608163544` | PASS，4/4 分片 SUCCESS，目标 16 行；RUNNING cancel HTTP 200 后 `FAILED/WORKER_EXECUTION_CANCELLED` | 未覆盖 kill worker、PG 断链、DIRECT copy 中断恢复。 |
| import checkpoint crash-resume | `scripts/sim/25-import-stage2e-checkpoint-crash.sh`，`import-stage2e-checkpoint-crash-20260608163051` | PASS，kill worker 后同 instance/pipeline 续跑，`markerBeforeKill=350`，最终 `processedFinal=20000 rows=20000` | 只覆盖 worker crash after chunk；PG/Kafka 断链另做故障注入。 |
| trigger pending auto catch-up | `scripts/sim/24-trigger-stage6d.sh`，`trigger-stage6d-20260608163850` | PASS，misfire pending 自动关联 catch-up request，`pending=185 request=29315 status=LAUNCHED|30302` | 高频 cron/1w storm 仍归容量 profile。 |

## 仍未完成

| 项 | 状态 | 原因 / 下一步 |
|---|---|---|
| 真实 S3 / OSS export | 未完成 | 本地只有 MinIO/S3-compatible；不能冒充生产同类 OSS。下一步需要真实 endpoint、bucket、凭证和 checksum 口径。 |
| dispatch / atomic 真实外部故障注入 | 部分完成 | 本地 API 500、LOCAL/NAS/SFTP、atomic HTTP/SQL timeout/shell cancel 已复跑；还缺真实 SFTP/NAS/OSS/HTTP timeout、断连、权限失败、重试/DLQ 组合。 |
| process failure profile | 部分完成 | RUNNING cancel 已完成；还缺 DIRECT copy 中途 kill worker、PG 临时断开、恢复后 staging/脏数据核对。 |
| 10w task storm | 未完成 | 1w 已完成；10w 属容量上限画像，应单独窗口跑，避免本机资源噪声误导生产承诺。 |
| 多租户公平性混压 | 未完成 | 还缺 ta/tb/tc 大小租户并发、quota 公平、RLS 串租和 tenant 维度监控拆分。 |
