# 本地压测记录：launch 受理与 Console 查询（2026-11-15）

## 结论摘要

本轮把本地后端压测链路实际跑通，但**当前宿主机不是可采信的基准环境**：8 核 macOS 负载均值在 15–26 之间，同时运行 8 个 JVM、Docker Desktop 与 OrbStack 两套容器运行时及桌面应用。Gatling 的注入结果与受控并发探测相互矛盾，因此不将 Gatling 数字当作容量结论。

可采信的本轮证据：

- launch 受控阶梯 5–300 并发：150 并发内全部 200、0 429，吞吐约 75–90 rps；150 并发 p95 3.5 s，200/300 并发开始按 `maxConcurrency+queue` 上限返回 429。
- Gatling 1 用户/秒时 launch **p95 62 ms、0 错误**，说明单请求链路本身正常。
- 同一租户、日历和业务日的 launch 由 **批次日结算 advisory fence** 串行化；并发时 PostgreSQL 出现最多 30 个会话等待同一 `Lock/advisory`。这是设计内的结算协调栅栏，也构成同日 launch 的容量上限来源。
- Worker 四类业务链路：20 用户/类全部 20/20 成功；50 用户/类全部 50/50 成功（IMPORT 50,000 行、DISPATCH 50 文件），p95 2.4–3.0 s，清理残留 0。整轮 50 用户耗时 16.5 分钟，主要受单 Workflow 同日串行排队影响。
- 调度混合场景 2 rps 下无 WAITING/READY 积压，launch p95 130 ms、scheduler snapshot p99 110 ms。
- ATOMIC 派发 2 用户/秒：122 请求、0 错误，p95 193 ms；122 个实例与任务全部 SUCCESS，`workflow_launch_wait` 峰值 54 后全部排空。
- 与 2026-10-31 历史压测相比：100/s 吞吐 74.3 vs 75.31 rps，天花板一致；50/s p95 1,707 ms vs 216 ms，**未达到历史延迟水平**，差异集中在排队/延迟，归因于宿主负载、开发 JVM 参数与同日结算栅栏，未证明代码回归。
- Console 读路径 Gatling 场景 p99 达 2.3–4.2 s；但受控并发下 20 并发达 **189 rps、p99 125 ms**，80 并发仍约 180 rps、p99 666 ms，说明 Gatling 读数字主要受客户端调度影响。

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

### Worker 业务端到端（各 50 用户，容量阶梯）

```bash
BATCH_CONSOLE_TOKEN=... BATCH_CONSOLE_CSRF_TOKEN=... \
RUN_ID=ltw-four50-20261115b WORKER_MODULES_CSV=import,export,dispatch,process \
USERS_PER_WORKER=50 IMPORT_PROFILE=medium PIPELINE_MAX_POLLS=300 \
WAIT_TERMINAL_TIMEOUT_SECONDS=1800 \
  bash load-tests/scripts/run-worker-load-tests.sh
```

结果（报告：`load-tests/target/worker-load-report-ltw-four50-20261115b.md`，窗口 02:25:46–02:42:17 UTC）：

| 作业 | 实例 | SUCCESS | 失败/未终态 | avg | p95 |
|---|---:|---:|---:|---:|---:|
| `import_customer_job` | 50 | 50 | 0 | 1.421 s | 2.609 s |
| `export_settlement_job` | 50 | 50 | 0 | 1.577 s | 3.032 s |
| `lt_dispatch_local_job` | 50 | 50 | 0 | 1.058 s | 2.408 s |
| `lt_process_sql_job` | 50 | 50 | 0 | 1.548 s | 2.742 s |

业务计数：`import_loaded_rows=50,000`，`dispatch_records=50`，`dispatch_files_dispatched=50`；四类任务全部 SUCCESS，清理后 `trigger_request` 残留为 0。

首次 50 用户尝试使用 90 次轮询（180 s），IMPORT 只完成 36/50；延长到 300 次（600 s）后 50/50 全部收敛。每实例数据库侧 avg/p95 约 1–3 s，整轮 16.5 分钟主要消耗在单 Workflow 对同日运行的串行排队，而不是单个任务执行时间。

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

每档 200 次 launch（150/200/300 并发档为 300 次），唯一幂等键，记录 HTTP 状态与 `time_total`：

| 并发 | 请求 | 成功 | 吞吐 | p50 | p95 | max |
|---:|---:|---:|---:|---:|---:|---:|
| 5 | 200 | 200 | 43.7 rps | 66 ms | 265 ms | 517 ms |
| 10 | 200 | 200 | 34.8 rps | 231 ms | 564 ms | 693 ms |
| 20 | 200 | 200 | 52.1 rps | 331 ms | 673 ms | 717 ms |
| 30 | 200 | 200 | **88.1 rps** | 297 ms | **363 ms** | 389 ms |
| 50 | 200 | 200 | 88.6 rps | 306 ms | 1,977 ms | 2,113 ms |
| 80 | 200 | 200 | 74.9 rps | 450 ms | 2,298 ms | 2,499 ms |
| 100 | 200 | 200 | 75.5 rps | 631 ms | 2,357 ms | 2,499 ms |
| 150 | 300 | 300 | 70.2 rps | 928 ms | 3,513 ms | 4,031 ms |
| 200 | 300 | 174 | 120.7 rps | 989 ms | 2,036 ms | 2,184 ms |
| 300 | 300 | 188 | 102.6 rps | 735 ms | 2,475 ms | 2,766 ms |

受控阶梯下 100 并发仍全部 200，没有 429；服务端受理吞吐稳定在约 75–90 rps。p95 在 30 并发内低于 500 ms，50 并发起尾部超过 2 s。宿主机负载使各档之间有波动（10 并发反而低于 5 并发），不宜把单档当作精确拐点；但“服务端远高于 Gatling 5 用户/秒 36.7% 拒绝所暗示的容量”可以确认。Gatling 的 429 是客户端调度抖动后集中补发，不是服务端在 5 rps 就饱和。

150 并发仍全部 200，但 p95 3.5 s、max 4.0 s，已接近 5 s 队列等待上限；200/300 并发开始出现 429（分别 126 和 112 次），与本地准入预算 `maxConcurrency=32 + queueCapacity=128 ≈ 160` 在途上限一致，属于 `QUEUE_FULL` 设计内拒绝。

### Console 读路径受控阶梯（curl）

同一 `/api/console/queries/instances`（租户 `ta`，pageSize=20），每档 300 次请求：

| 并发 | 请求 | 成功 | 吞吐 | p50 | p95 | p99 |
|---:|---:|---:|---:|---:|---:|---:|
| 10 | 300 | 300 | 127.9 rps | 44 ms | 100 ms | 313 ms |
| 20 | 300 | 300 | **189.1 rps** | 75 ms | 111 ms | **125 ms** |
| 30 | 300 | 300 | 112.3 rps | 190 ms | 439 ms | 541 ms |
| 50 | 300 | 300 | 140.5 rps | 284 ms | 407 ms | 522 ms |
| 80 | 300 | 300 | 180.2 rps | 345 ms | 452 ms | 666 ms |

读路径在受控并发下同样没有错误，20 并发时达到约 189 rps、p99 125 ms；80 并发仍维持约 180 rps，p99 666 ms。Gatling 读场景出现 2–4 s p99 与这里的差距，进一步说明其客户端在高负载宿主机上不能作为延迟基准。

### 与历史压测对比（2026-10-31）

历史记录：`docs/verifications/load-test-local-capacity-2026-10-31.md`，同一 `JobLaunchSimulation`，租户 `ta`、固定业务日、`BATCH_TRIGGER_API_LAUNCH_QUEUE_WAIT_MILLIS=5000`。本轮用 Python 开放模型按绝对时间发送，避免 Gatling 客户端调度抖动：

| 注入 | 历史结果 | 本轮结果 |
|---|---|---|
| 50/s | 3,050 请求、0 KO，p95 **216 ms** | 3,000 请求、28 KO（0.9%），p95 **1,707 ms**，send lag p95 479 ms |
| 100/s | 6,100 请求、0 KO，吞吐 75.31 rps，p95 554 ms（D 轮） | 3,000 请求、209 KO（7.0%），吞吐 **74.3 rps**，p95 5,003 ms |

**结论**：吞吐天花板与历史一致（约 74–75 rps），但 50 rps 下没有达到历史的 p95 216 ms，且 100 rps 下尾部直接顶到 5 s 队列上限。差异集中在延迟/排队，而不是受理能力总量。

补充：三租户/三业务日并行（`ta`/`tb`/`tc` 各 20 并发、300 请求）聚合 **69.5 rps**，单 lane 23–26 rps、p95 2.0–2.4 s，仅 3 次 429。把锁分散到不同租户和业务日后聚合吞吐没有超过单 lane 的约 75 rps 上限，说明当前瓶颈是共享的宿主/Trigger 资源，而不只是同日栅栏。

本轮环境与历史的主要差异：

- 宿主机 8 核、load average 10–26，同时运行 8 个 JVM、Docker Desktop 与 OrbStack；历史记录未给出同等负载基线。
- 本地应用使用 `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC` 的开发参数；历史记录未记录 JVM 参数，无法确认是否一致。
- 同日 launch 事务被 `batch.acquire_batch_day_settlement_fence` 串行化；本轮并发探测中 PostgreSQL 出现成批 `Lock/advisory` 等待，历史记录未采集该指标。
- 本轮数据库已累积数千条压测请求；历史每轮数据量更小。

因此当前不能宣称“达到历史 50 rps 延迟水平”，也不能据此判定为代码回归；需要在空闲 runner、生产类 JVM 参数和干净数据上复跑同一开放模型，才能做同条件对比。

## 诊断

- **批次日结算栅栏串行化**：`TriggerRequestMapper.acquireBatchDaySettlementFence` 调用 `batch.acquire_batch_day_settlement_fence(tenant, calendar, bizDate)`，对同一租户+日历+业务日取 `pg_advisory_xact_lock`。并发探测期间 `pg_locks` 最多有 30 个会话等待同一 key，持锁事务覆盖整个 launch 事务。这是同日多次 launch 的串行点，也是 Gatling 高注入下 429 的直接原因；多业务日/多租户可分散该锁。
- **客户端注入受宿主机负载影响**：8 核机器 load average 15–26，Gatling 与 8 个 JVM、桌面应用共享 CPU；5 用户/秒出现 p50 3.7 s 而 1 用户/秒 p95 62 ms，符合客户端调度抖动后集中补发的特征。该轮 Gatling 数字不作为服务端容量证据。
- **应用运行参数非生产形态**：本地 JVM 使用 C1-only 和 SerialGC，读路径 p99 尾部可能受 GC 与 JIT 影响；不能据此推断生产延迟。
- Trigger JVM 在 30 并发探测中 CPU 仅 8–14%，PostgreSQL 未出现 CPU 饱和，未发现连接池耗尽或 Kafka 异常。

## 数据与清理

- 本轮 Gatling 产生 `batch.trigger_request` 中 `trace_id like 'lt-%'` 共 1,462 条，`batch.job_instance` 同条件 74 条；受控 curl 探测另有约 4,300 条当日 `trigger_request`。
- 未执行运行态清理，避免影响本地既有租户和定义数据；这些记录仅存在于本地开发库。
- 8 个应用、PostgreSQL、Kafka、Valkey、MinIO 仍在运行，供继续压测或手工排查。
- 四类 Worker 负载已运行 20 用户/类与 50 用户/类两档（IMPORT/EXPORT/DISPATCH/PROCESS），全部实例 SUCCESS；报告分别为 `worker-load-report-ltw-four20-20261115b.md` 与 `worker-load-report-ltw-four50-20261115b.md`，夹具与运行数据均按 RUN_ID 清理，`trigger_request` 残留为 0。
- ATOMIC 派发已运行（2 用户/秒，122 实例/任务全部 SUCCESS）；受控加压的实例/任务按 `trace_id like 'lt-spi-%'` 保留，未按 RUN_ID 清理。
- 未运行五类 Worker 的 1w/10w 容量画像、ATOMIC task storm 与 soak；这些需要独立数据集和更长稳定窗口。

## 2026-11-16/17 专用 benchmark 1w 复核

按 [benchmark.yml](../../deploy/docker/compose/benchmark.yml) 与 [benchmark.env](../../config/ops-governance/benchmark.env) 启动隔离拓扑：2 Orchestrator、Trigger、Atomic Worker、Console，Kafka launch 12 分区 / dispatch 24 分区；PostgreSQL 使用 `synchronous_commit=off`、`max_wal_size=4GB`、`checkpoint_timeout=15min`、`wal_compression=pglz`。host→container 切换需要一次性 `BATCH_STORAGE_BACKEND_CUTOVER_ID` 和 `BATCH_QUOTA_BACKEND_CUTOVER_ID`。

### 30 req/s（10,020 请求，100 业务日）

- HTTP：10,018 成功，2 次 60 s 客户端超时（0.02%），p95 **546 ms**
- 终态：脚本停止时 220/320 实例终态，10 FAILED、98 RUNNING
- 失败原因：`EXTERNAL_OUTCOME_UNKNOWN`；Worker 日志显示 REPORT 到 Orchestrator 返回 500 或 `Request cancelled`，benchmark 关闭了 report outbox，租约回收后标记为结果未知

### 25 req/s（10,000 请求，100 业务日）

- HTTP：10,000 成功，0 KO，p95 **631 ms**，p99 3,071 ms
- 终态：脚本停止时 SUCCESS 277、RUNNING 75、FAILED 21
- 同样出现 REPORT 500 / I/O timeout，`non_idempotent_started_at` 为空，executor 未执行
- 仅 373/10,000 实例被创建，其余仍在 `workflow_launch_wait` / PROCESSING，说明 run 推进和 REPORT 能力已成为瓶颈

### 结论与边界

- benchmark PG 调优消除了上一轮普通 `.env.local` 下的连接池打满和 8.5% 429，但没有让 1w 达到“0 错误 + 全终态 SUCCESS”。
- 失败集中在 Orchestrator REPORT 路径和 run 推进吞吐，不是 atomic SQL 业务失败；未发现可归因于 #1400/#1405/#1408/#1409 的新增回归。
- 当前 8 核 / 8 GB Docker 拓扑不足以作为 1w 通过证据；10w 未尝试。若要继续，需要增加 Orchestrator/Worker 容量或独立 runner，并保留 `benchmark.env` 的 PG/Kafka 参数。
- 本轮结束后已停止 benchmark 应用、恢复普通 `.env.local` 基础设施与本地宿主栈；`p2capacity` 运行态已清理，定义/租户保留。

### 2026-11-17 行锁车队取证与修复

100 rps、100 业务日短压后采样 `pg_stat_activity`：

- 多个 `SELECT ... FROM trigger_request ... FOR UPDATE` 等待 36 秒以上
- 持有者处于 `idle in transaction`，最后一条 SQL 是 `workflow_run` 查询
- 线程栈显示 `WorkflowLaunchWaitReconciler` 与 Kafka launch 重复处理同一请求

根因是同一 `trigger_request` 被 Kafka 投递和 launch-wait 恢复同时 launch，后来者阻塞等待行锁；`claimDue` 的 5 秒重试租约短于一次 launch 的排队时间，进一步放大重复认领。

修复（#1424）：

- 准入行锁改为 `FOR UPDATE NOWAIT`，重复 launch 快速让出
- launch-wait 认领增加独立 `claimLeaseSeconds`（默认 120s），可重试失败时释放
- 容量等待快照在取锁前加密，锁内复核不再做 KMS 解密

修复后同条件复测：50/100 rps 各 3,000 请求全部成功，100 rps p95 144ms；`pg_blocking_pids` 为 0，`idle in transaction` 采样为 0。

### 2026-11-18 修复后 1w 终态收敛复核

目的：确认 #1424 合并后，10,000 请求 / 100 业务日的 1w 是否从“能受理但不收敛”变为可收敛。

环境：

| 项 | 值 |
|---|---|
| 代码版本 | `main` @ `715a18159` |
| 应用镜像 | 按 HEAD 重建，4 个容器 `org.opencontainers.image.revision` = `715a18159…` |
| 拓扑 | `benchmark.yml` + `benchmark.env`：2 Orchestrator × 6 consumer、Trigger、Atomic Worker、Console；Kafka launch 12 分区 / dispatch 24 分区 |
| PostgreSQL | `synchronous_commit=off`、`max_wal_size=4GB`、`checkpoint_timeout=15min`、`wal_compression=pglz` |
| 宿主机 | 8 核；host load 1m 均值 20.5、峰值 69.2（压测脚本判定不具备基线可比资格） |

命令（admin 会话同时提供 `BATCH_CONSOLE_TOKEN` / `BATCH_CONSOLE_CSRF_TOKEN`）：

```bash
RUN_ID=p2-atomic-1w-20261118a STORM_TOTAL_REQUESTS=10000 STORM_RPS=25 \
  RUN_10W_STORM=1 RUN_FAIRNESS=0 CAPACITY_BIZ_DATE_CARDINALITY=100 \
  SKIP_AUTO_CLEANUP=1 bash load-tests/scripts/run-p2-capacity-profile.sh
```

结果：

| 指标 | 值 |
|---|---|
| launch HTTP | 10,000 请求 / 10,000 OK / 0 KO，吞吐 24.81 rps，p95 2,458 ms、p99 5,388 ms、max 13.6 s |
| 根运行 | 10,000 创建、10,000 终态（`control-run-terminal-counts.sql` 四项均 10,000） |
| 业务结果 | `job_instance` 10,000 全部 `SUCCESS`，0 FAILED；非成功分区 0 |
| REPORT 错误 | Orchestrator 0 次 5xx / `Request cancelled`；Atomic Worker 0 ERROR |
| 收敛时长 | 流量 07:16:52–07:23:35（+08），最后一个实例 08:14:46 完成 → 流量结束后约 51 分钟 |
| 脚本判定 | 2,400 s 等待窗口在 9,952/10,000 结束（`exit_code=1`），剩余 48 个在窗口后约 6.5 分钟收敛 |

启动链路与锁：

- launch 阶段均值：validation 139 ms、prepare 236 ms、dispatch 344 ms、plan 18 ms、resource 49 ms、materialize 134 ms、transition 75 ms（主 Orchestrator 3,387 次）
- `pg_blocking_pids` 采样基本为 0，偶发 1–3 个瞬时行锁等待；无 `FOR UPDATE` 车龙，无 `idle in transaction` 堆积
- advisory 锁峰值等待 12.7 s、lock waiters 峰值 23，均为批次日结算栅栏与调度器既有串行点，不是请求行锁
- claim 延迟 avg 4.5 s / p95 17.2 s；实例时长 avg 6.0 s / p95 22.6 s
- 每次 launch 都至少被 defer 一次（10,000 API + 10,000 EVENT 重放），说明单 Workflow × 业务日只允许一个活跃运行的约束决定了 1w 的吞吐：100 个业务日槽位 × 运行时长

结论与边界：

- 修复后 1w **可以 100% 收敛且 0 失败**，与此前 1w 的失败形态（REPORT 500、`EXTERNAL_OUTCOME_UNKNOWN`、大量未终态）不同。
- 但默认 2,400 s 等待窗口不足；同等条件复跑需把 `STORM_WAIT_SECONDS` 提高到 3,600 s 才能让脚本自身判定通过。
- 本轮 host load 峰值 69.2、数据库 CPU 约 3 核，脚本已标记不具备基线可比资格，不能作为 staging/生产容量结论。
- 10w 与多租户公平性未在本轮执行；`p2capacity` 运行态按 `SKIP_AUTO_CLEANUP=1` 保留。

## 复现边界

- 本轮是本地开发环境证据，不是 staging/生产容量结论；宿主机负载和本地 JVM 参数使其不适合作为发布门禁。
- 若要得到可比较的容量画像，应在空闲的专用 runner 上重跑，并用 `BIZ_DATE_CARDINALITY` 或分租户方式绕开同日结算栅栏的串行影响，同时采集 PostgreSQL 等待事件、连接池和 Kafka 积压。
- 读路径 SLO 断言（p99 < 300 ms）本轮未通过；在干净环境下需要重新建立基线后再判断是否需要调优。

## 2026-11-19 Tier 0 单集群调优与 10w 验证

目标：在 benchmark 拓扑上验证“连接池/入口许可调优 + 10 万请求”能否收敛，量化单 PostgreSQL 的拐点。

### 实施

- Trigger 入口预算（`deploy/docker/compose/benchmark.yml`）：`maxConcurrency 32→64`、平台连接池 `40→80`、队列容量保持 128（受 `TriggerRuntimeProperties` 的 `@Max(128)` 约束）。
- 修复 Worker 状态回写：心跳不再接受 ONLINE 状态下的自报 DRAINING，避免 `resume` 后下一次心跳把状态改回排空（`DefaultWorkerRegistryService.resolveHeartbeatStatus`）。
- PostgreSQL 使用 `benchmark.env` 参数：`synchronous_commit=off`、`max_wal_size=4GB`、`checkpoint_timeout=15min`、`wal_compression=pglz`、`max_connections=300`。

### 结果

| 轮次 | 注入 | 请求 | 429 | p95 | 结论 |
|---|---|---:|---:|---:|---|
| 短探针（调优前 32/128/40） | 50 rps × 120 s | 10,000 | 689（6.9%） | — | 入口队列饱和 |
| 短探针（调优后 64/128/80） | 50 rps × 120 s | 6,000 | 207（3.45%） | 6.0 s（p99 10.1 s） | 429 明显下降，但延迟超 5 s 预算 |
| 10w | 25 rps × 约 17 min | 13,710 | **0** | — | **无法收敛**：`job_instance` avg 28.3 s、p95 61.1 s（1w 时为 2.3 s / 6.0 s）；完成速率 1.3–4/s，`workflow_launch_wait` 积压 6,000–7,600 |

10w 期间的数据库侧证据（`pg_stat_activity` 采样）：

- PostgreSQL CPU 3.5–6.3 / 8 核；`active=69`、`blocked=32`
- 21 个会话等待 advisory 锁（批次日结算栅栏，分布在约 7 个 key，非单一热点）
- `LWLock: LockManager` 21 个会话；`batch.trigger_request` 上 autovacuum 单次 8 s
- `job_task`：READY 49（平均等待 29.9 s）、RUNNING 27——Worker 未饱和，瓶颈在控制面/PG

### 结论与边界

- 调优后**入口不再是瓶颈**（25 rps 0 拒绝；50 rps 仅 3.45% 拒绝）。
- 单 PostgreSQL 在持续 25 rps、10 万请求规模下**无法在合理窗口内收敛**：运行时长被放大一个数量级，积压持续增长。1w 在 3.7 分钟收敛的结论仍然成立，但它只代表 1/10 体量。
- 10w 在本机单库上不可作为容量结论；需要更低注入率、更大/更快的 PG（或读副本分担）以及 Tier 1 控制面分片，属于下一阶段。

## 2026-11-19 Tier 0.5 单集群瓶颈归因（A/B/C/D 对照）

Tier 0 显示入口拒绝已为 0、但 25 rps × 10w 不收敛，说明瓶颈在入口之后。本轮用四组对照隔离“连接池/entry 并发”“autovacuum”“outbox 串行发布”三类假设，为控制面分片提供 Go/No-Go 依据。

### 方法

同一镜像（`4bba27ba3+t0-heartbeat-fix`）、同一 benchmark 拓扑、每组一棵独立租户；10,000 请求 @ 25 rps、100 个业务日、`CAPACITY_MAX_ACTIVE_RUNS_PER_BIZ_DATE=1`、`STORM_WAIT_SECONDS=300`、`SKIP_AUTO_CLEANUP=1`。数据库为 `benchmark.env` 口径（`synchronous_commit=off`、`max_wal_size=4GB`、`checkpoint_timeout=15min`、`wal_compression=pglz`）。

### 结果

| 轮次 | 变量 | 入口接纳 | launch p95 | `job_instance` avg / p95 | 备注 |
|---|---|---|---:|---|---|
| A 基线 | 64 并发 / 平台池 80 / 单 outbox 分片 / 默认 autovacuum | 10,000 OK / 0 KO | 5,256 ms（p99 9,130） | 29.8 s / 77.3 s | PG CPU 4.1/8，2 个 autovacuum 活跃 |
| B | A + 放宽 autovacuum（0.1/0.05、naptime 60 s、workers 3） | 6,685 OK / 3,315 KO | 4,292 ms（OK 子集） | 20.9 s / 45.5 s | KO 为连接拒绝/提前关闭，有效负载更低 |
| C | B + outbox 2 分片（每 Orchestrator 一片，batch 256）+ 关闭 SLA/监控扫描 | 7,095 OK / 2,905 KO | 5,036 ms（OK 子集） | 15.4 s / 38.1 s | 仍有连接中断；advisory 等待 11–21 会话，outbox 发布 avg 5.14 s |
| D | C + 入口并发 64→32、平台连接池 80→40 | **10,000 OK / 0 KO** | **2,408 ms（p99 5,361）** | **4.09 s / 15.33 s** | 10,000 `workflow_run` 与 10,000 `job_instance` 全部 SUCCESS；PG CPU 3.2/8，advisory 等待 2、`LWLock: LockManager` 7 |

### 归因

- **决定性变量是入口并发与连接池预算，而不是“入口许可不够”。** C→D 只改并发 64→32、池 80→40，`job_instance` 均值 15.4→4.1 s、p95 38.1→15.3 s，同时把 30% 的入口失败降到 0。
- 降低并发后 PG 侧争用同步下降：中段采样 advisory 等待 11–21 → 2、`LWLock: LockManager` 大幅回落、PG CPU 4.1 → 3.2 核。说明 64 个并发 launch 事务同时争抢批次日结算栅栏与连接配额，是放大器而不是吞吐来源。
- B/C 的“改善”不能单独引用：两组分别有 33%/29% 的连接中断，实际注入低于名义值，时长下降里有一部分来自负载减少。只有 A 与 D 是 0 KO 的干净对照。
- outbox 发布仍是串行点：D 期间每次发布平均约 717 ms、约 32 个事件；单分片约 22 事件/s，只是本轮不再是主导项。

### 收敛与边界

- 脚本 300 s 窗口仍然不足：D 在窗口内为 8,972/10,000 终态（`exit_code=1`），到 14:53:04 全部收敛——10,000/10,000 SUCCESS、`workflow_launch_wait` 清零，流量结束后约 13 分钟。
- 与 2026-11-18 的 1w（avg 2.33 s / p95 5.97 s）仍有 1.75x / 2.57x 差距；两轮都是 10,000 请求，但业务日基数和夹具细节未逐项对齐，未隔离因素主要是 outbox 单分片发布节奏、数据库统计信息与本地宿主机负载，不能归因给单一配置。
- 本轮为本地 8 核单机证据，不是 staging/生产容量结论；`p2capacity4` 运行态按 `SKIP_AUTO_CLEANUP=1` 保留后清理。

### 容量含义

- 单 PostgreSQL 在 25 rps、100 业务日、单日 1 个活跃运行的约束下可持续承接，PG CPU 约 40%，阻塞会话 < 10，未触发分片评估中的 Go 条件（CPU 持续 > 80% 或阻塞 > 20）。
- 100k 体量（25 rps × 约 66 分钟）未在本轮直接执行。按 D 的稳定接纳与不增长积压外推可以收敛，但在拿到真实 100k 运行证据前不得作为容量结论。

## 2026-11-19 Tier 0.6：100k 注入与两个硬上限

Tier 0.5 证明 32/40 + outbox 2 分片能在 25 rps 下完成 1w。本轮把注入推到 100,000 请求，检验单租户 10w 能否收敛，并把数据库容量与业务/管线约束分开。

### 运行 1：默认槽位（`max_active_runs_per_biz_date=1`，tenant `p2capacity5`）

- 25 rps 注入 13 分钟后为 19,726 请求（19,714 OK / 12 × 429），人工停止：结论已确定，避免继续空跑。
- 100 个业务日槽位、同时 `RUNNING` 75–77；run 时长 avg 18.1 s / p95 41.5 s（最近 200 个 avg 26.4 s）。
- 吞吐 ≈ 100 槽 / 18–26 s ≈ **4–5.5 run/s**；`workflow_launch_wait` 积压 8,361 且线性增长。
- PG CPU 3.4/8 核；`job_task` READY 32 / RUNNING 23（Worker 空闲）。
- 结论：默认槽位规则下单租户 25 rps 不可能收敛；约束是业务并发规则，不是数据库或 Worker。

### 运行 2：放宽槽位（`max_active_runs_per_biz_date=8`，tenant `p2capacity6`）

- 25 rps × 4,000 s 注入完成：100,000 请求，**96,093 OK / 3,907 KO（3.91%）**，平均吞吐 24.66 rps。
- KO 构成：429 × 3,376（15 s 准入排队超时）、500 × 501（Trigger 平台连接池获取失败）、客户端 60 s 超时 × 30。
- launch p95 2,799 ms（OK 子集 1,877 ms）、p99 9,030 ms。
- 槽位积压消失：`workflow_launch_wait` 全程为 0。
- launch 管线成为新上限：Orchestrator launch 事务约 **0.95 s**（validation 115、prepare 217、dispatch 340、plan 18、resource 57、materialize 133、transition 67 ms），并发为 launch topic 12 分区 × 每 Orchestrator 6 consumer × 2 实例 = **12**，理论上限约 **12.6 run/s**。
- 实测 launch 速率从 11.4 退化到 4.0 run/s（20:34→21:28 分段），入口 25 rps 的差额进入 Kafka/ACCEPTED 积压；注入结束时仍有 60,943 条 ACCEPTED 未 launch。
- 已完成部分：35,120 个 run（34,922 SUCCESS）、35,130 个 job，avg 6.15 s / p95 14.34 s；PG CPU 3.4–5.5/8 核。
- 结论：100k 在 90 分钟窗口内不可能收敛；限制是控制面 launch 并发 × 事务时长，不是 PG CPU 或 Worker。

### 归因与容量含义

- 单租户持续吞吐 ≈ `min(业务槽位 / run 时长, launch 并发 / launch 事务时长)`。
- 本拓扑两项分别约为：默认槽位 4–5.5/s；launch 管线 11–12.6/s，且随积压增长退化到约 4/s。
- 要让 25 rps 持续收敛，需要同时满足：`max_active_runs_per_biz_date ≥ 25 × run 时长`（约 6–8）且 launch 并发 ≥ 25（≥ 25 个分区与并发 launch 消费者），或把 launch 事务压到 0.4 s 以内。
- 按租户组整库分片能提高多租户总吞吐，但**不改变单租户上限**；单租户 25 rps 必须扩控制器并发或缩短 launch 事务。

### 本轮发现的问题

- 过载时 Trigger 平台连接池耗尽返回 **500**（`CannotGetJdbcConnectionException`），而不是 503/429 的可重试语义；`TriggerApiExceptionHandler` 只映射了 `TriggerSchedulerBusyException`。
- SLA 监控扫描按 10 分钟周期运行，在本机数据量下多次语句超时；容量压测当前只拉长了间隔，未彻底关闭，属于测量噪声。
- 脚本 1,800 s 终态窗口不足以覆盖 100k 的尾部队列；若要判定 100k，需要按积压/速率单独计算收敛时间。

### 边界

- 本地 8 核单机，拓扑为单 Trigger + 2 Orchestrator + 1 Atomic Worker + Console；不是 staging/生产容量结论。
- 运行 2 的人工终止发生在注入完成后，收敛过程被中断，因此没有“100k 全量终态”的证据；这不影响“窗口内无法收敛”的结论。

### 对照：分区/消费者扩容（2026-11-19 夜间，已回滚）

假设：launch 管线限 11–12.6/s 是并发不足，扩分区与消费者即可抬到 25/s。

配置：launch topic 12→24 分区，两个 Orchestrator 各 6→12 consumer（共 24 并发）。其余与 Tier 0.6 相同（入口 32/40、槽位 8、outbox 2 分片）。

结果（10,000 请求 @ 25 rps，tenant `p2capacity7`，RUN_ID `p2-t0-a24-20261119h`）：

| 指标 | 12 分区 / 6 consumer（Tier 0.6） | 24 分区 / 12 consumer |
|---|---|---|
| launch 速率 | 11.4 run/s | **3.3 run/s** |
| `job_instance` avg / p95 | 5.9 s / 14.8 s | **98.7 s / 158.8 s** |
| 同时 RUNNING 的 run | 46–71 | 567（上限 800） |
| PG CPU | 3.4–5.5 / 8 核 | 2.2 / 8 核 |
| 等待 | advisory 0–5、LockManager 7 | advisory 31、LockManager 27 |

数据库侧定位：`pg_stat_activity` 中 30 个会话同时在执行批次日结算栅栏 SQL（`select case when cast($1 as date) ... target.calendar_code`），另有 6 个会话等待 `acquire_batch_day_settlement_fence`。并发翻倍后，每个 launch 的栅栏查询互相拖慢，单次 launch 事务从约 0.95 s 退化到数秒，净吞吐反而下降 3.4 倍。

结论：

- **launch 并发是放大器而不是吞吐来源**，与 Tier 0.5 中“入口并发 64→32”的结论一致；当前限制在每次 launch 的栅栏查询与事务成本。
- 提升单租户 launch 吞吐应转向压缩 launch 事务（减少栅栏查询次数、合并 prepare/dispatch、缩小结算围栏范围），而不是继续加分区/消费者。
- 已回滚：`benchmark.yml` 回到 12 分区 / 每实例 6 consumer；本地 topic 重建为 12 分区。容量脚本保留新的 `CAPACITY_TRIGGER_CONSUMER_CONCURRENCY` 参数（默认 6），用于以后做对照时不必再改脚本。
- 第一次 24/12 尝试在宿主 load 67（Docker VM）下进行、第二次在 load 19–33 下进行；两次都是同一形态的退化，且数据库侧的栅栏争用与宿主负载无关，因此结论保留，但绝对吞吐数值只作方向性参考。
- 2026-11-20 决定：压缩 launch 事务（预估 3–5 天）暂不实施，单租户按约 11–12 run/s 作为当前接受能力；若未来业务确实需要更高单租户吞吐，再评估该方案或垂直扩库。

### 对照：launch 消息按业务日分区（2026-11-20，已回滚）

假设：把 launch 消息 key 从 `tenantId:requestId` 改成 `tenantId:bizDate`，同一业务日由一个 consumer 顺序处理，消除批次日结算栅栏的并发争抢；配合 24 分区 × 每实例 12 consumer。

结果（10,000 请求 @ 25 rps，`max_active_runs_per_biz_date=8`，tenant `p2capacity9`，RUN_ID `p2-t0-datekey-20261120b`，`pg_stat_statements` 开启）：

| 指标 | 12 分区 / 6 consumer（基线） | 按日分区 + 24/12 |
|---|---|---|
| launch 速率 | 11.4 run/s | **5.9 run/s** |
| `job_instance` avg | 5.9–6.2 s | **87.7 s** |
| 同时 RUNNING 的 run | 46–71 | 622 |
| `job_task` READY 积压 | 32 | 538 |
| PG CPU | 3.4–5.5 / 8 核 | 3.3 / 8 核 |
| 等待 | advisory 0–5、LockManager 7 | advisory 12、LockManager 4（单看 launch 确实下降） |

数据库侧：栅栏 SQL 在运行中累计 6,791 次、均值 **665 ms**、最大 20.5 s——按日分区只让准入不再抢锁，栅栏等待并没有消失。

机制（代码证据）：

- 准入路径取栅栏：`TriggerRequestPersistence` 的 `acquireBatchDaySettlementFence` 与 `trigger_request` 的 BEFORE INSERT 触发器。
- 节点派发也取同一个栅栏：`DefaultWorkflowNodeDispatchService.java:134`。
- 运行回报也取同一个栅栏：`WorkflowRunReportLock.java:43`。

所以它是**整个运行生命周期**的栅栏，不只是准入锁。把准入串行化后，系统放进更多 run（RUNNING 46–71 → 622），节点派发与回报在同一个日期锁上排队，run 时长从 6 s 涨到 88 s，净吞吐反而下降。

结论：

- 单纯调整准入的消息顺序/分区不能解决瓶颈；**必须缩短栅栏持有时间或缩小栅栏范围**（压缩 launch 事务 / 合并事务 / 让派发与回报不持有同一把全局日期锁）。
- 已回滚：消息 key 恢复 `tenantId:requestId`，`benchmark.yml` 恢复 12 分区 / 每实例 6 consumer，本地 topic 重建为 12 分区，代码与单测恢复到基线（`TriggerOutboxRelayTest` 22/22 通过）。
- 这次对照说明 Tier 0.6 的“launch 并发是放大器”结论比原先更普适：并发放大的是整个生命周期的日期栅栏争用。

## 2026-11-20 批次日结算栅栏共享锁改造验证

改动：V387 新增 `acquire_batch_day_settlement_fence_shared`，准入、节点派发与运行回报改用共享读者锁；开日、人工操作与结算保持独占。方案与判据见[栅栏共享锁改造](../plans/batch-day-fence-shared-lock-2026-11-20.md)。

### 12 分区 / 6 consumer（tenant `p2capacity10`，10,000 请求 @ 25 rps，槽位 8）

- 入口：10,000 请求 / 10,000 OK / 0 KO，launch p95 **457 ms**（改造前 2,408–2,799 ms）。
- 业务结果：10,000/10,000 `SUCCESS`、0 失败；`job_instance` avg **4.71 s** / p95 **11.96 s**；claim 延迟 avg 3.24 s / p95 8.66 s。
- 收敛：等待窗口内 10,000/10,000 终态（`exit_code=0`）。
- 栅栏 SQL：均值 **2.5 ms**、最大 457 ms（改造前均值 417 ms、最大 15.9 s）。
- launch 阶段合计约 0.88 s：validation 96、prepare 206、dispatch 321、materialize 130、transition 65、resource 48、plan 16 ms。

### 24 分区 / 12 consumer（tenant `p2capacity11`，同一注入）

- launch 速率 **5.0 run/s**，同时 RUNNING 684，`job_instance` avg **91.5 s**。
- `job_task` READY 640、RUNNING 59、Worker CPU 50%；PG 4.3/8 核，无持续锁等待；栅栏均值 7.2 ms。
- 结论：栅栏不再是瓶颈，高并发下的新瓶颈是单 Worker 的 claim 领取能力。benchmark 保持 12/6，直到 Worker claim 容量提升。

### 并发语义测试

- 新增 `BatchDayFenceLockModeIntegrationTest`（真实 PostgreSQL）：两个共享读者互不阻塞、结算独占等待共享读者排队、结算窗口内共享读者排队并观察到提交后的状态；3/3 通过。
- 回归：`JobNodeDispatchIntegrationTest` 100/100 通过（需带 `-am` 重新构建依赖，否则会命中本地旧制品）。

### 边界

- 本地 8 核单机，benchmark 拓扑；不是 staging/生产容量结论。
- 两次 24/12 对照（随机 key、按日 key、共享锁）都低于 12/6 基线，差异来自下游领取与运行时长，不是栅栏本身。

## 2026-11-21 Worker claim 容量与 outbox 延迟归因

栅栏共享锁上线后，24/12 对照把瓶颈指向 Worker 领取。本轮用两组运行把 claim 与 outbox 分开测量：一组低速率干净运行拿基线，一组原 25 rps 洪峰复现积压。应用镜像为 `eb026c671+fence-shared-dirty`（含 V387），benchmark 拓扑 12 分区 / 每实例 6 consumer、Atomic 24 listener / `max-poll-records=5` / `batch-claim=5`。

### 运行 A：600 请求 @10 rps（干净基线）

`RUN_ID=p2-p1-outbox-20261121e`，tenant `p2capacity14`，槽位 64，自动清理关闭。600/600 实例 SUCCESS、0 失败，实例 avg 0.669 s / p95 1.653 s，launch p95 49 ms；12/12 launch 分区均分到消息。预检宿主 load 5.0–6.8，运行期峰值 1 分钟 load 8.45（1.06/CPU）。

| 指标 | 数值 |
|---|---|
| claim-batch 调用 / 认领任务 | 536 / 600，**有效批 1.12**，最大 2 |
| claim-batch HTTP 均值 | **23.5 ms**（12.60 s / 536） |
| outbox advance 轮次 / 事件 | 302 / 1,800，平均批 5.96 |
| outbox DB select 均值 | **7.0 ms** |
| outbox 单轮 publish（poll+Kafka+回写） | **39.7 ms**（其中 Kafka+回写约 32.7 ms） |
| `ATOMIC` 事件 created→published | avg 0.255 s / p50 0.221 s / p95 0.590 s / max 1.195 s |
| `JOB_TERMINAL` / `WORKFLOW_TERMINAL` | avg 0.212 s / p50 0.190 s / p95 0.436 s / max 1.346 s |

### 运行 B：10,000 请求 @25 rps（洪峰复现）

`RUN_ID=p2-p1-claim-20261121c-10w`，tenant `p2capacity12`，槽位 8，单一业务日。宿主在注入期被压到 load 峰值 39，属于受污染运行，只用于分解延迟，不作为容量基线。

- 入口：10,000 请求中 9,029 OK、**971 × 429（9.7%）**；launch p95 **25.3 s**、均值 3.58 s。
- 业务侧：约 400 s 内创建 1,782 个实例（≈4.5 run/s）；1,801 个任务 SUCCESS，任务执行 avg 0.556 s / p95 2.011 s，READY→开始 avg 1.266 s。
- claim：1,147 次 claim-batch / 1,205 个任务，**有效批 1.05**；claim-batch HTTP 均值 **262 ms**（301.0 s / 1,147）。
- outbox：786 轮 / 3,616 个事件，平均批 4.60；DB select 均值 **46 ms**，单轮 publish 均值 **357 ms**（Kafka+回写约 311 ms）。
- 事件延迟：`ATOMIC` avg 1.408 s / p50 0.813 s / p95 3.917 s / max 27.5 s；终态事件 avg 1.197 s / p50 0.718 s / p95 3.473 s / max 21.2 s。

### 归因

**claim 容量**

- 两组的有效批都只有 **1.05–1.12**：Kafka 每次 poll 通常只交付 1–2 条，`batch-claim` 的 HTTP 摊销基本不存在；调大批量不会提高领取并发。
- claim 本身很快：干净运行 **23.5 ms**，受污染运行 262 ms；即使按 262 ms 计算，24 listener 的 claim 理论上限也约 91 次/s，远高于观测值。
- 真正的上限是 **listener 执行占用**：单 listener 串行执行已认领任务，单 Worker 吞吐 ≈ listener 数 ÷（claim RTT + 任务时长）。按干净运行 0.669 s 估，24 listener 约 34 任务/s；运行 B 的 4.5 run/s 是入口/宿主受限，不是 claim 饱和。
- 11-20 的 24/12 对照（READY 640、5.0 run/s）同样是执行占用受限。可选杠杆按代价排序：增加 Worker 副本 / dispatch 分区与 listener 并发 → 缩短任务时长 → listener 内并行执行（须先验证 lease/report 与顺序语义）。单 Worker 的 claim 能力不是当前首要瓶颈。

**outbox 延迟**

- 干净运行单轮 39.7 ms，其中 DB select 7 ms、Kafka 与状态回写 32.7 ms；事件 p50 190–221 ms、p95 436–590 ms。
- 洪峰运行单轮涨到 357 ms，事件 p50 718–813 ms、p95 3.47–3.92 s。p50 约等于“等待下一轮轮询的一半间隔 + 单轮处理”，p95 尾部来自自适应轮询间隔（最小 200 ms、空闲退避 ×1.5 至 5 s）叠加单轮成本。
- 结论：延迟由**轮询排队 + 单轮处理成本**决定，不是 DB 查询，也不是 Kafka 吞吐本身；事件量越大、单轮越慢，尾部越长。若业务要求更低的派发 p95，可评估“outbox 写入后立即触发一轮”或限制空闲退避上限；当前没有 SLO 支撑，不改生产默认值，benchmark 也维持原配置。

### 边界

- 本地 8 核单机 benchmark 拓扑；运行 B 宿主 load 峰值 39，绝对速率与 429 比例不可作为容量结论，只用于延迟分解。
- 运行 A 的宿主 load 仍略高于 0.75/CPU 的可比阈值，因此标记 `standard-baseline-comparable=no`；它的价值是低负载下的相对分解，不是生产容量承诺。
- 本轮未覆盖多 Worker 副本、任务时长变化和 listener 内并行；这三点是后续验证 Worker 扩展方案的入口。

## 2026-11-21 业务键基数校准（补充）

claim/outbox 归因后，继续追 launch 上限。先用 `pg_stat_statements` 做 100 请求画像，再用同一拓扑做 1,500 请求 @25 rps 的业务键基数 1 vs 100 对照。

### 画像证据

临时以 `shared_preload_libraries=pg_stat_statements`、`track_io_timing=on` 重建 postgres 主库（完成后已恢复默认），100 请求画像的 Top SQL：

| 语句 | calls | total | mean |
|---|---:|---:|---:|
| `select 1 from (select pg_advisory_xact_lock(hashtext($1), hashtext($2))) guard` | 100 | 88,041 ms | **880 ms** |

该语句来自 `ResultVersionWriter.lockBusinessKey`，锁键是 `(tenantId, businessKey)`。容量脚本原默认 `CAPACITY_BIZ_DATE_CARDINALITY=1`，所有运行共用同一业务键，结果版本写入被串行化，任务完成变慢并占住活跃槽位，进而压低 launch 吞吐。

### 对照

同一 12 分区 / 6 consumer、1 Worker、槽位 64、1,500 请求 @25 rps：

| 指标 | 基数 1 | 基数 100 |
|---|---:|---:|
| 任务完成速率 | 8.96 /s | **15.74 /s** |
| 任务执行 avg | 0.957 s | 1.023 s |
| launch 总耗时 | 约 1,659 ms（含日志开销） | **765 ms** |
| launch 已埋点 phase | — | 485 ms |
| 未归因 | — | 280 ms |
| 12 consumer 理论上限 | — | 12 ÷ 0.765 ≈ **15.7/s** |

基数 100 时“12 consumer ÷ 765 ms”与实测 15.74/s 吻合；单 Worker 理论容量约 24.5/s，仍有约 1.5 倍余量。

### 结论与调整

- 基数=1 是热点键画像，不能作为吞吐基线；`CAPACITY_BIZ_DATE_CARDINALITY` 默认改为 100，脚本在 ≥1,000 请求且基数=1 时输出告警。
- 24/12 对照早先得到的 5.0 run/s 主要受该夹具串行化影响，不能直接归因于 Worker claim 容量。
- 真正的端到端上限仍是 launch：12 consumer × 单次 launch wall time；下一步是 launch 24/12 对照与压缩 280 ms 未归因成本，Worker 扩容等到 launch 超过约 24/s 或迁到多宿主环境。
- 热点业务键在真实租户中仍会串行化结果版本写入，这是版本排序语义的一部分，容量规划需按业务键基数评估。

### 边界

- 单机 8 核、应用与 PG 共享宿主；基数 100 对照运行时宿主负载偏高，绝对速率只作方向性参考，相对对照使用同一拓扑。
- 画像运行开启了全语句日志，日志开销使绝对延迟不可用；最终结论采用 `pg_stat_statements` 与基数对照，不采用该轮绝对时间。
- 未覆盖多宿主、多 Worker 副本与真实业务键分布。
