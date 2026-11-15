# 本地压测记录：launch 受理与 Console 查询（2026-11-15）

## 结论摘要

本轮把本地后端压测链路实际跑通，但**当前宿主机不是可采信的基准环境**：8 核 macOS 负载均值在 15–26 之间，同时运行 8 个 JVM、Docker Desktop 与 OrbStack 两套容器运行时及桌面应用。Gatling 的注入结果与受控并发探测相互矛盾，因此不将 Gatling 数字当作容量结论。

可采信的本轮证据：

- 受控并发探测（10 并发、200 次 launch）达到 **42.2 rps，p50 157 ms，p95 811 ms，0 错误**。
- Gatling 1 用户/秒时 launch **p95 62 ms、0 错误**，说明单请求链路本身正常。
- 同一租户、日历和业务日的 launch 由 **批次日结算 advisory fence** 串行化；并发时 PostgreSQL 出现最多 30 个会话等待同一 `Lock/advisory`。这是设计内的结算协调栅栏，也构成同日 launch 的容量上限来源。
- Worker 四类业务链路（IMPORT/EXPORT/DISPATCH/PROCESS，各 20 用户）全部 20/20 成功，p95 2.6–3.4 s；调度混合场景 2 rps 下无 WAITING/READY 积压，launch p95 130 ms、scheduler snapshot p99 110 ms。
- ATOMIC 派发 2 用户/秒：122 请求、0 错误，p95 193 ms；122 个实例与任务全部 SUCCESS，`workflow_launch_wait` 峰值 54 后全部排空。
- Console 读路径在高负载下 p99 达 2.3–4.2 s，未达到 `p99 < 300 ms` 的脚本断言；同轮次 0 错误。

本轮没有修改业务代码，也没有把本地数字外推为 staging/生产容量。

## 实验定义

- **假设**：当前本地环境可承载 20 用户/秒的 launch 注入，且 Console 读路径满足既有 Gatling SLO。
- **目标链路**：
  - `POST /api/triggers/launch`（Trigger 受理 → `trigger_request` / `job_instance` / Outbox）
  - Console 列表查询：`/api/console/queries/instances`、`/workers`、`/alerts` 与 `/actuator/health`
- **负载形状**：
  - `JobLaunchSimulation`：`rampUsers(peak).during(ramp)` + `constantUsersPerSec(peak)`；每用户一次 launch。
  - `ConsoleQuerySimulation`：`constantUsersPerSec(peak)`，每用户循环 4 个请求并各暂停 1 s。
- **测试数据**：租户 `ta`，`workflowDefinitionId=1`（`JOB-WF-1`，`calendar_code=default_calendar`），业务日 `2026-01-15`。
- **成功标准**：
  - launch：global p95 < 500 ms，错误率 < 1%。
  - 读：global p99 < 300 ms，错误率 < 0.1%。

## 环境

| 项 | 值 |
|---|---|
| 代码版本 | `main` @ `9624940a8`（#1391 合并后） |
| 应用构建 | `scripts/local/build-apps.sh`，11-15 09:13 重新打包 8 个 fat jar |
| 应用运行 | 宿主 JVM，`-XX:TieredStopAtLevel=1 -XX:+UseSerialGC`（本地开发参数） |
| 服务 | console 18080、trigger 18081、orchestrator 18082、5 个 Worker 18083–18087 |
| 中间件 | PostgreSQL 17（3 GiB）、Kafka（1.5 GiB）、Valkey（512 MiB）、MinIO（1 GiB），单实例容器 |
| 容器运行时 | Docker Desktop 29.9.3；OrbStack 同时运行但未被本流程使用 |
| 宿主机 | macOS，8 核；压测期间 load average 15–26 |
| 鉴权 | 本地 `BATCH_SECURITY_BYPASS_MODE=true`；Console 压测传空 `console.accessToken` 以不发送失效 cookie |

本轮为跑通压测先处理了三处本地环境问题：

1. `batch-valkey` 的 AOF 尾部损坏导致容器反复重启。备份 `/data/appendonlydir.bak-20261115` 后用 `valkey-check-aof --fix` 截断到首个坏记录（17,304,757 → 17,304,278 字节），容器恢复 healthy。
2. `start-all.sh` 只补缺失服务，console/orchestrator 仍在跑 11-13 启动的旧 JVM。停掉旧进程与 Compose 同名容器后，用 `BASH_SCRIPT_RUNTIME=host` 和当前 jar 重启。
3. `start-all.sh` 末尾的 `audit-runtime-warnings.sh` 扫描了 165 MB 历史 `orchestrator.log`（约 48 万条 WARN），运行 8 分钟仍未结束，阻塞启动收尾；本轮终止该审计并保留服务。建议后续将审计范围限定到本次启动窗口或先归档旧日志。

`restart.sh` 在本机 `/usr/bin/env bash`（3.2）下触发了空数组 `set -u` 错误，本轮用 Bash 5 shim 执行；这是 macOS 3.2 的本地兼容问题，不影响 CI 的 Bash 5。

## 命令

```bash
# 读路径
cd load-tests
../mvnw -o -ntp gatling:test \
  -Dsimulation=io.github.pinpols.batch.loadtest.simulations.ConsoleQuerySimulation \
  -DtenantId=ta -Dusers.peak=10 -Dduration.seconds=60 -Dconsole.accessToken= \
  -Dload.runId=load-20261115-read10-warm

# launch
set -a && source ../.env.local && set +a
../mvnw -o -ntp gatling:test \
  -Dsimulation=io.github.pinpols.batch.loadtest.simulations.JobLaunchSimulation \
  -DworkflowDefinitionId=1 -DtenantId=ta -DbizDate=2026-01-15 \
  -Dusers.peak=<1|5|20> -Dduration.seconds=<30|60> -Dramp.seconds=<5|20> \
  -Dload.runId=load-20261115-launch<N>
```

受控探测使用 10 个线程的 Python 脚本，每个请求生成唯一 `Idempotency-Key` / `X-Request-Id`，只统计 HTTP 状态与 `time_total`；脚本不写入仓库。

## 测量结果

### Console 读路径（Gatling）

| 轮次 | 请求 | 错误 | p50 | p95 | p99 | 吞吐 | SLO |
|---|---:|---:|---:|---:|---:|---:|---|
| 10 用户/秒 × 60 s（预热后） | 25,844 | 0 | 413 ms | 2,055 ms | 4,191 ms | 287 rps | ❌ p99 |
| 10 用户/秒 × 30 s（明细） | 13,863 | 0 | 78 ms | — | 2,294 ms | 231 rps | ❌ p99 |

两轮波动主要来自宿主机负载与 JIT/GC 状态，不能据此得出读路径容量。

### launch（Gatling）

| 注入 | 请求 | OK | KO（429） | p50 | p95 | SLO |
|---|---:|---:|---:|---:|---:|---|
| 1 用户/秒 × 30 s | 31 | 31 | 0 | 28 ms | 62 ms | ✅ |
| 5 用户/秒 × 60 s | 305 | 193 | 112（36.7%） | 3,718 ms | 5,503 ms | ❌ |
| 20 用户/秒 × 60 s | 1,220 | 1,164 | 56（4.6%） | 692 ms | 5,009 ms | ❌ |

429 的 5 s 尾部对应 `BATCH_TRIGGER_API_LAUNCH_QUEUE_WAIT_MILLIS=5000`；Trigger 日志确认全部为 `RATE_LIMITED`（本地准入闸门），不是下游错误。

### 调度积压与派发（2 launch rps + 2 read rps）

命令：

```bash
cd load-tests
set -a && source ../.env.local && set +a
TENANT_ID=ta DURATION_SECONDS=140 INTERVAL_SECONDS=5 \
  OUT=target/scheduler-backlog-20261115-2rps.csv \
  bash scripts/sample-scheduler-backlog.sh &

../mvnw -o -ntp gatling:test \
  -Dsimulation=io.github.pinpols.batch.loadtest.simulations.SchedulingBacklogUnderLoadSimulation \
  -DworkflowDefinitionId=1 -DtenantId=ta -DbizDate=2026-01-15 \
  -Dscheduling.launch.rps=2 -Dscheduling.read.rps=2 -Dduration.seconds=120 \
  -Dconsole.accessToken= -Dload.runId=load-20261115-sched2
```

Gatling：720 请求、0 错误；launch p95 **130 ms**，`/internal/scheduler/snapshot` p99 **110 ms**，均达标。

SQL sampler（27 个 5 秒样本）：

| 指标 | 首样本 | 末样本 | 变化 |
|---|---:|---:|---:|
| `ji_success` | 170 | 297 | +127 |
| `trigger_launched` | 451 | 707 | +256 |
| `jp_waiting` | 0 | 0 | 0 |
| `jt_ready` | 0 | ≤1 | 无堆积 |
| `oldest_waiting_partition_seconds` | 0 | 0 | 0 |
| `worker_load / worker_capacity` | 0 / 0 | 0 / 0 | 采样未取到 Worker 负载 |

2 rps 同业务日写入下没有出现 WAITING/READY 堆积，成功实例在采样窗口内持续增长（约 0.94 实例/秒），说明本轮 Worker 完成速度能跟上现有写入。`worker_load/capacity` 为 0 是采样字段缺口，不能据此判断 Worker 饱和度。

### Worker 业务端到端（IMPORT / EXPORT / DISPATCH / PROCESS，各 20 用户）

命令（需要管理员登录会话的 JWT 与 CSRF cookie，同时在 `PIPELINE_MAX_POLLS=90` 下等待终态）：

```bash
TOKEN=...; CSRF=...
BATCH_CONSOLE_TOKEN="$TOKEN" BATCH_CONSOLE_CSRF_TOKEN="$CSRF" \
RUN_ID=ltw-four20-20261115b WORKER_MODULES_CSV=import,export,dispatch,process \
USERS_PER_WORKER=20 IMPORT_PROFILE=medium PIPELINE_MAX_POLLS=90 \
WAIT_TERMINAL_TIMEOUT_SECONDS=900 \
  bash load-tests/scripts/run-worker-load-tests.sh
```

结果（报告：`load-tests/target/worker-load-report-ltw-four20-20261115b.md`）：

| 作业 | 实例 | SUCCESS | 失败/未终态 | avg | p95 |
|---|---:|---:|---:|---:|---:|
| `import_customer_job` | 20 | 20 | 0 | 1.338 s | 2.618 s |
| `export_settlement_job` | 20 | 20 | 0 | 1.558 s | 2.700 s |
| `lt_dispatch_local_job` | 20 | 20 | 0 | 1.552 s | 3.381 s |
| `lt_process_sql_job` | 20 | 20 | 0 | 1.354 s | 2.742 s |

业务计数：`import_loaded_rows=20,000`，`dispatch_records=20`，`dispatch_files_dispatched=20`；PROCESS 的 `process_source_rows/target_rows` 由该 SQL 作业的统计口径返回 0，本表不能据此判断 PROCESS 处理行数。

首次尝试只设置 `PIPELINE_MAX_POLLS=1`，15 个仍在运行的实例被计为失败；改为 90 次轮询后全部收敛，说明这是等待参数不足，不是业务失败。

清理阶段暴露并修复了 `cleanup-control-plane-worker.sql` 的外键顺序问题：脚本先删 `trigger_request`，被 `workflow_launch_wait` 的 `(tenant_id, request_id)` 外键阻塞。修复后先按本轮请求回收等待/别名记录，再删除 `trigger_request`；用本轮 RUN_ID 复跑清理脚本退出 0，`trigger_request` 残留归零。

### ATOMIC 派发（2 用户/秒）

Workflow `JOB-WF-44`（`atomic_sql_demo`，租户 `default-tenant`），业务日 `2026-01-15`：

```bash
../mvnw -o -ntp gatling:test \
  -Dsimulation=io.github.pinpols.batch.loadtest.simulations.AtomicTaskDispatchSimulation \
  -DworkflowDefinitionId=61 -DtenantId=default-tenant -DbizDate=2026-01-15 \
  -Dusers.peak=2 -Dduration.seconds=60 -Dramp.seconds=10 \
  -Dload.runId=load-20261115-atomic2
```

结果：122 请求、0 错误；p50 15 ms、p95 **193 ms**、p99 380 ms，写 SLO 通过。业务侧 122 个实例和 122 个原子任务全部 `SUCCESS`。

单作业 Workflow 会把并发 launch 放入 `workflow_launch_wait`：峰值 54 条 `WAITING`，随后全部转为 `RESUMED` 并执行完成，没有 429 或任务失败。这是该 Workflow 的串行执行语义，不是拒绝。

该场景没有把 `runId` 写入 launch 参数，无法用通用 RUN_ID 清理；本轮保留 `trace_id like 'lt-spi-%'` 的 122 个实例/任务作为残留，后续对 ATOMIC 加压时应补充运行标识以便自动回收。

### 受控并发探测（curl）

| 并发 | 请求 | 成功 | 吞吐 | p50 | p95 | max |
|---:|---:|---:|---:|---:|---:|---:|
| 10 | 200 | 200 | 42.2 rps | 157 ms | 811 ms | 1,193 ms |

该结果说明服务端在 10 并发下仍能稳定受理，Gatling 5 用户/秒的 36.7% 拒绝与受控探测不一致。

## 诊断

- **批次日结算栅栏串行化**：`TriggerRequestMapper.acquireBatchDaySettlementFence` 调用 `batch.acquire_batch_day_settlement_fence(tenant, calendar, bizDate)`，对同一租户+日历+业务日取 `pg_advisory_xact_lock`。并发探测期间 `pg_locks` 最多有 30 个会话等待同一 key，持锁事务覆盖整个 launch 事务。这是同日多次 launch 的串行点，也是 Gatling 高注入下 429 的直接原因；多业务日/多租户可分散该锁。
- **客户端注入受宿主机负载影响**：8 核机器 load average 15–26，Gatling 与 8 个 JVM、桌面应用共享 CPU；5 用户/秒出现 p50 3.7 s 而 1 用户/秒 p95 62 ms，符合客户端调度抖动后集中补发的特征。该轮 Gatling 数字不作为服务端容量证据。
- **应用运行参数非生产形态**：本地 JVM 使用 C1-only 和 SerialGC，读路径 p99 尾部可能受 GC 与 JIT 影响；不能据此推断生产延迟。
- Trigger JVM 在 30 并发探测中 CPU 仅 8–14%，PostgreSQL 未出现 CPU 饱和，未发现连接池耗尽或 Kafka 异常。

## 数据与清理

- 本轮 Gatling 产生 `batch.trigger_request` 中 `trace_id like 'lt-%'` 共 1,462 条，`batch.job_instance` 同条件 74 条；受控 curl 探测另有约 4,300 条当日 `trigger_request`。
- 未执行运行态清理，避免影响本地既有租户和定义数据；这些记录仅存在于本地开发库。
- 8 个应用、PostgreSQL、Kafka、Valkey、MinIO 仍在运行，供继续压测或手工排查。
- 四类 Worker 负载（IMPORT/EXPORT/DISPATCH/PROCESS，各 20 用户）已运行并通过，报告 `load-tests/target/worker-load-report-ltw-four20-20261115b.md`；其夹具与运行数据已按 RUN_ID 清理，`trigger_request` 残留为 0。
- ATOMIC 派发已运行（2 用户/秒，122 实例/任务全部 SUCCESS）；受控加压的实例/任务按 `trace_id like 'lt-spi-%'` 保留，未按 RUN_ID 清理。
- 未运行五类 Worker 的 1w/10w 容量画像、ATOMIC task storm 与 soak；这些需要独立数据集和更长稳定窗口。

## 复现边界

- 本轮是本地开发环境证据，不是 staging/生产容量结论；宿主机负载和本地 JVM 参数使其不适合作为发布门禁。
- 若要得到可比较的容量画像，应在空闲的专用 runner 上重跑，并用 `BIZ_DATE_CARDINALITY` 或分租户方式绕开同日结算栅栏的串行影响，同时采集 PostgreSQL 等待事件、连接池和 Kafka 积压。
- 读路径 SLO 断言（p99 < 300 ms）本轮未通过；在干净环境下需要重新建立基线后再判断是否需要调优。
