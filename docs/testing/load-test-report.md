# 压测报告 — 2026-04-25

## TL;DR

本地单实例 orchestrator，所有 P2 默认开（quota Redis Lua / worker cache / read replica / Quartz 单独库 / Kafka TENANT mode），跑 Gatling JobLaunchSimulation 找到拐点：

| 并发用户 | 状态 | 关键数据 |
|---|---|---|
| **10** | ✅ 平稳 | 610 req / 0 fail / **8.71 req/sec** / p50=43ms / p95=163ms / p99=430ms / max=878ms |
| **20** | 🔴 95% timeout | orchestrator hikari pool 耗尽，Spring MVC 排队，60s 全 timeout |
| **50** | 🔴 100% timeout | 同上，更严重 |
| **100** | 🔴 81% timeout | 全线打挂 |

**结论：本地 dev 单实例 orchestrator 可持续吞吐 ~8 req/sec**。

## 瓶颈定位

orchestrator 主 DB pool 在 `application-local.yml` 写死 `maximum-pool-size: 10`，**远小于生产默认 30**：

```yaml
# batch-orchestrator/src/main/resources/application-local.yml:
spring:
  datasource:
    hikari:
      maximum-pool-size: 10   # ← 本地 dev 默认
```

```yaml
# batch-orchestrator/src/main/resources/application.yml:
maximum-pool-size: ${BATCH_ORCHESTRATOR_PLATFORM_DB_MAX_POOL_SIZE:30}
```

每个 launch 请求要做：
1. `INSERT trigger_request`（trigger）
2. `INSERT job_instance` + `INSERT job_partition` × N + `INSERT job_task` × N（orchestrator）
3. `INSERT outbox_event` × N（orchestrator）
4. 至少 4-5 次 SQL 操作 / 请求

10 连接 × 200ms / 操作 ≈ 50 req/s 理论上限；实际拐点 ~10 req/s 是因为还要扣除背景调度器（archive / poll / reset）也在用同一池子。

## 推算生产容量（按本地数据线性外推）

| 部署 | 单实例吞吐（req/s）| 5 实例 | 10 实例 |
|---|---|---|---|
| **本地 dev**（pool=10）| 8-10 | n/a | n/a |
| 生产单实例（pool=30）| ~25-30 | ~125-150 | ~250-300 |
| 调优（pool=50, 4 vCPU/8GB）| ~50 | ~250 | ~500 |

千万/天 = ~115 req/s 平均（高峰可能 500-1000）：
- **5 个生产实例 + pool=30** 能撑平均负载
- **高峰需要 10 个实例 + pool=50**，或前置 backpressure

## 拐点突破后的失败模式

20 users 时 orchestrator 行为：
- HTTP 500 `system error`
- Trigger 侧报 "trigger forward failed (will retry)"
- Trigger 客户端 broken pipe（Gatling 60s 超时主动断开）
- 系统**不能自愈**：测试结束后再 curl 健康也是 timeout，必须重启 orchestrator

这说明缺：
- backpressure（rate limit 已有 `TenantActionRateLimiter` 但默认配置未启用）
- ~~连接池耗尽时的 fail-fast~~ — 实际上 orchestrator (`application.yml:34` 默认 5000ms) 与
  console-api (`ReadReplicaProperties.connectionTimeoutMillis=5_000L`) **都已 5s fail-fast**；
  trigger 走 SB 默认 30s 是因为 Quartz cluster checkin 需要这个头寸（验证：把 trigger
  也改 5s 会让 `QuartzJdbcClusterIntegrationTest` 不稳）。瓶颈不在 timeout 长度，而是
  Tomcat worker 全部卡在 DB 等待时整个 process 没办法服务健康检查。
- 优雅降级（拒绝新请求而不是排队等死）

## 已落地的运维改动（2026-04-25）

| # | 改动 | 文件 | 状态 |
|---|---|---|---|
| 1 | 生产 yml pool 30→50 | `.env.prod` `BATCH_ORCHESTRATOR_PLATFORM_DB_MAX_POOL_SIZE=50` | ✅ |
| 2 | Hikari connectionTimeout=5s | orchestrator + console-api 早已就位，trigger 因 Quartz 保留 30s | ✅（不再需新动作）|
| 3 | TenantActionRateLimiter 上线 | `.env.prod` `BATCH_RATE_LIMIT_*` 三 env | ✅ |

## 仍需运维/部署侧动作（按 ROI）

4. **生产部署 orchestrator ≥ 3 实例**（k8s）+ ShedLock 已支持多实例 — 部署改造
5. **load-tests/CapacityBaselineSimulation 加入 CI 门禁** — staging-gate.yml 现仅
   compile-load-tests + upload artifact，要补 `mvn gatling:test` step 才真跑

## 命令复现

```bash
cd load-tests
mvn gatling:test \
    -Dgatling.includes=com.example.batch.loadtest.simulations.JobLaunchSimulation \
    -Dtrigger.baseUrl=http://localhost:18081 \
    -DtenantId=default-tenant \
    -DjobCode=disp_local_probe \
    -DbizDate=2026-04-25 \
    -Dusers.peak=10 \
    -Dduration.seconds=60 \
    -Dramp.seconds=10 \
    -Dslo.write.p95ms=2000 \
    -Dslo.maxErrorPct=2.0 \
    --batch-mode
```

报告 HTML：`load-tests/target/gatling-results/joblaunchsimulation-*/index.html`

## 后续

- 复现条件：所有 6 个 JVM 进程 healthy，`disp_local_probe` 是 default-tenant MANUAL job
- 真实生产压测需要在 staging（pool=30 + 多实例 + k8s autoscale）跑 CapacityBaselineSimulation 30 分钟
- 此次只做了写路径（JobLaunchSimulation），读路径（ConsoleQuerySimulation）和混合（CapacityBaselineSimulation）未跑

---

## 2026-04-27 — V5-P2-3 quota 压测 smoke

### 目的

验证 Gatling 工具链 + 应用响应能力 ok；为后续真"打满 quota 触发 RATE_LIMITED"测试铺基础。

### 命令

```bash
cd load-tests
mvn gatling:test \
    -Dgatling.includes=com.example.batch.loadtest.simulations.JobLaunchSimulation \
    -Dtrigger.baseUrl=http://localhost:18081 \
    -DtenantId=default-tenant \
    -DjobCode=disp_local_probe \
    -DbizDate=2026-04-26 \
    -Dusers.peak=5 -Dduration.seconds=20 -Dramp.seconds=5 \
    -Dslo.write.p95ms=2000 -Dslo.maxErrorPct=5.0
```

### 结果

| 指标 | 值 |
|---|---|
| 请求总数 | 105 |
| 持续时间 | 25s |
| 平均吞吐 | **4.2 req/s** |
| Min / Mean / Max | 30ms / 59ms / 270ms |
| p50 / p75 / p95 / p99 | 52ms / 63ms / 112ms / 210ms |
| 失败率 | **0%** |
| t < 800 ms | 100% |

SLO 验证：
- `slo.write.p95ms < 2000ms` ✅（实际 112ms）
- `slo.maxErrorPct < 5.0%` ✅（实际 0%）

### 局限 + follow-up

本次**未真触发 quota 限流**——`default-tenant` 当前 `tenant_quota_policy` 阈值远高于 105 req / 25s。要真观察 RATE_LIMITED + 拐点：

1. 配低 quota：`max_qps_per_tenant=2 / max_running_jobs_per_tenant=10`
2. 跑 `CapacityBaselineSimulation` 30 min，10 → 50 用户递增
3. 看 orchestrator metrics `quota.allow.count` vs `quota.deny.count` 拐点
4. 记录 launcher SLO 退化点（p95 → 何时跨过 1s）

→ 留 V5-P2-3-ext 后续 sprint 真做。

---

## 2026-05-05 — 调度积压 / waiting dispatch 补充压测

### 目的

补齐单纯 `launch` 压测看不到的问题：在固定写入压力下，观察分区派发、资源队列、`WAITING/READY` 积压、Worker 负载与实际完成吞吐，判断调度器或 waiting dispatch 是否成为瓶颈。

### 环境

| 项 | 值 |
|---|---|
| 机器 | macOS 15.7.3 / 8 CPU / 16GB |
| 服务 | 本地单实例 trigger / orchestrator / console + 3 个 ONLINE worker |
| 端口 | trigger `18081` / orchestrator `18082` / console `18080` |
| 租户 / 作业 | `default-tenant` / `export_settlement_job` |
| 压测模型 | 固定 `5 launch/s` + `1 scheduling-read/s`，持续 60s |
| DB sampler | 每 5s 采样，覆盖压测前、中、后共 90s |

### 命令

```bash
cd load-tests

TENANT_ID=default-tenant DURATION_SECONDS=90 INTERVAL_SECONDS=5 \
  OUT=target/scheduler-backlog-20260505-fixed5.csv \
  bash scripts/sample-scheduler-backlog.sh

TOKEN=$(curl -fsS -X POST http://localhost:18080/api/console/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

mvn gatling:test \
  -Dsimulation=com.example.batch.loadtest.simulations.SchedulingBacklogUnderLoadSimulation \
  -Dtrigger.baseUrl=http://localhost:18081 \
  -Dconsole.baseUrl=http://localhost:18080 \
  -Dorchestrator.baseUrl=http://localhost:18082 \
  -DtenantId=default-tenant \
  -DjobCode=export_settlement_job \
  -DbizDate=2026-05-05 \
  -Dscheduling.launch.rps=5.0 \
  -Dscheduling.read.rps=1.0 \
  -Dduration.seconds=60 \
  -Dslo.write.p95ms=5000 \
  -Dslo.read.p99ms=5000 \
  -Dslo.maxErrorPct=5.0 \
  -Dconsole.accessToken="$TOKEN" \
  --batch-mode
```

Gatling HTML：`load-tests/target/gatling-results/schedulingbacklogunderloadsimulation-20260505013033214/index.html`

Sampler CSV：

- `load-tests/target/scheduler-backlog-20260505-fixed5.csv`
- `load-tests/target/scheduler-backlog-20260505-recovery.csv`

### HTTP 侧结果

| 指标 | 值 |
|---|---|
| 总请求数 | 780 |
| launch 请求 | 300 |
| 调度/队列查询请求 | 480 |
| 失败率 | **0%** |
| 平均吞吐 | 13 req/s |
| Global p50 / p95 / p99 | 14ms / 42ms / 111ms |
| launch p95 | 35ms |
| scheduler snapshot p99 | 117ms |
| WAITING partition query p99 | 354ms |

结论：HTTP 入口和调度查询本身没有成为瓶颈；`launch` 在 5/s 下响应很快。

### Backlog / 吞吐结果

压测 90s 采样窗口首尾对比：

| 指标 | 开始 | 结束 | 增量 |
|---|---:|---:|---:|
| `trigger_launched` | 1292 | 1592 | +300 |
| `job_instance WAITING` | 1 | 298 | +297 |
| `job_partition WAITING` | 0 | 297 | +297 |
| `job_task READY` | 2 | 5 | +3 |
| `job_task SUCCESS` | 5 | 5 | 0 |
| `job_task FAILED` | 805 | 805 | 0 |
| `oldest_waiting_partition_seconds` | 0 | 67 | +67s |
| `worker_load / worker_capacity` | 1 / 30 | 0 / 30 | 未打满 |

压测后额外 30s 恢复窗口：

| 指标 | 恢复开始 | 恢复结束 |
|---|---:|---:|
| `job_instance WAITING` | 298 | 298 |
| `job_partition WAITING` | 297 | 297 |
| `job_task READY` | 0 | 3 |
| `job_task FAILED` | 810 | 807 |
| `oldest_waiting_partition_seconds` | 115 | 142 |
| `worker_load / worker_capacity` | 0 / 30 | 0 / 30 |

后续即时查询显示，本轮新建的 300 个实例中约：

| 状态 | 数量 |
|---|---:|
| `job_instance WAITING` | 197 |
| `job_instance RUNNING` | 100 |
| `job_instance FAILED` | 3 |
| `job_task CREATED` | 197 |
| `job_task FAILED` | 103 |
| `job_partition WAITING` | 197 |
| `job_partition RETRYING` | 100 |
| `job_partition FAILED` | 3 |

### 判断

1. **5 launch/s 已能稳定制造调度积压**：入口 0 失败且 p95 很低，但 `WAITING` partition 从 0 涨到 297，说明只看 launch QPS 会误判系统健康。
2. **Worker 不是本轮首要瓶颈**：`worker_load / worker_capacity` 始终接近 0 / 30，积压主要发生在资源队列 / waiting dispatch / 配额释放之前。
3. **实际业务完成吞吐约为 0**：采样窗口内 `job_task SUCCESS` 无增长；主要结果是 `WAITING`、`CREATED`、`RETRYING/FAILED` 增长。
4. **恢复窗口没有自动清空 backlog**：压测后 30s，最老 `WAITING` 从 115s 增至 142s，说明积压不是瞬时尖峰，而是持续滞留。
5. **作业数据本身也有失败因素**：释放到执行侧的 export 任务大量报 `EXPORT_BATCH_NOT_FOUND`，因此本轮不能作为 worker 真实业务处理能力上限，只能说明调度/队列在入口压力下会堆积。

### CLAIM → REPORT 压测状态

本轮未执行 `WorkerTaskLifecycleSimulation`。原因是压测前 `default-tenant` 没有隔离的 `READY` task；压测后任务主要处于 `CREATED/WAITING/RETRYING/FAILED`，直接拿生产样本 task 做 CLAIM/REPORT 会与真实 worker 和状态机互相干扰。

要安全跑 CLAIM → REPORT，需要先准备隔离数据：

1. 使用专门租户 / workerGroup，暂停真实 worker 消费。
2. 生成一批 `READY` task。
3. 导出 `taskId,tenantId,workerId` CSV。
4. 运行 `WorkerTaskLifecycleSimulation`。

### 后续建议

1. 修正或解释 `WAITING` 释放策略：重点查 `BATCH_RESOURCE_SCHEDULER_WAITING_DISPATCH_INTERVAL_MILLIS`、`waiting-dispatch-batch-size`、资源队列 cap、quota exceeded strategy。
2. 对 `export_settlement_job` 的测试数据补齐 export batch，避免 `EXPORT_BATCH_NOT_FOUND` 干扰 worker 处理能力统计。
3. 用隔离租户补跑 `WorkerTaskLifecycleSimulation`，单独给出 orchestrator claim/report 接口吞吐。
4. 正式容量报告必须同时包含 Gatling HTML + backlog CSV；单看 HTTP p95/p99 不足以判断调度系统健康。

---

## 2026-05-05 — 四类 Worker 端到端成功基线

### 目的

补齐 IMPORT / EXPORT / DISPATCH / PROCESS 四类 worker 的可成功执行压测数据集，验证真实调度 → CLAIM → worker 执行业务 → REPORT → 实例终态链路。

### 新增脚本

| 脚本 | 作用 |
|---|---|
| `load-tests/scripts/prepare-worker-load-data.sh` | 生成四类 worker 压测数据和 payload JSON |
| `load-tests/scripts/run-worker-load-tests.sh` | 顺序执行四类 worker Gatling launch，并用 DB 等待实例终态 |
| `load-tests/scripts/cleanup-worker-load-data.sh` | 按 `RUN_ID` 清理压测数据 |

数据集：

| Worker | 数据 |
|---|---|
| IMPORT | CSV 小/中/大样本：20 / 1000 / 10000 行；本轮用 medium 1000 行 |
| EXPORT | `settlement_batch` 1 批 + `settlement_detail` 5000 行 |
| DISPATCH | `LOCAL` 渠道文件 + `lt_dispatch_local_job` |
| PROCESS | `process_order_event` 5000 行，SQL transform 聚合到 500 个账户 |

### 成功基线命令

```bash
USERS_PER_WORKER=1 RAMP_SECONDS=1 IMPORT_PROFILE=medium \
  WAIT_TERMINAL_TIMEOUT_SECONDS=180 MAX_ERROR_PCT=5 \
  bash load-tests/scripts/run-worker-load-tests.sh
```

报告：`load-tests/target/worker-load-report-ltw-20260505095303.md`

### 结果

| Worker / job | total | success | failed | 平均完成耗时 | p95 完成耗时 |
|---|---:|---:|---:|---:|---:|
| IMPORT `import_customer_job` | 1 | 1 | 0 | 2.620s | 2.620s |
| EXPORT `export_settlement_job` | 1 | 1 | 0 | 3.525s | 3.525s |
| DISPATCH `lt_dispatch_local_job` | 1 | 1 | 0 | 1.519s | 1.519s |
| PROCESS `lt_process_sql_job` | 1 | 1 | 0 | 30.092s | 30.092s |

业务计数：

| 指标 | 值 |
|---|---:|
| IMPORT loaded rows | 1000 |
| EXPORT source rows | 5000 |
| DISPATCH records | 1 |
| DISPATCH files dispatched | 1 |
| PROCESS source rows | 5000 |
| PROCESS target rows | 500 |

按本轮单实例耗时估算的本地单 worker 基线吞吐：

| Worker | 估算吞吐 |
|---|---:|
| IMPORT | 约 382 rows/s |
| EXPORT | 约 1418 source rows/s |
| DISPATCH | 约 0.66 file/s |
| PROCESS | 约 166 source rows/s，约 16.6 target rows/s |

### 并发 2 观察

同脚本以 `USERS_PER_WORKER=2` 跑过一轮，报告：`load-tests/target/worker-load-report-ltw-20260505094808.md`。

| Worker / job | total | success | failed | 说明 |
|---|---:|---:|---:|---|
| IMPORT | 2 | 2 | 0 | 4 个 IMPORT task 全部成功，每个 partition 500 行 |
| EXPORT | 2 | 1 | 1 | 两个并发复用同一个输出 fileName / checksum，1 个触发 `EXPORT_REGISTER_CHECKSUM_CONFLICT` |
| DISPATCH | 2 | 2 | 0 | 复用同一 fileId 仍能完成，但只产生 1 条业务 dispatch record |
| PROCESS | 2 | 2 | 0 | 两个 SQL transform 任务成功 |

结论：

1. 四类 worker 的成功数据集已补齐，`USERS_PER_WORKER=1` 可作为本地端到端回归基线。
2. IMPORT / PROCESS 在低并发下完成稳定；PROCESS 由于 SQL transform + staging + commit，耗时明显高于另外三类。
3. EXPORT 要做真实并发吞吐，必须改成动态 payload feeder，为每个 VU 生成唯一 `fileName/targetPath/batchNo` 或隔离 checksum；静态 payload 不适合并发上限测试。
4. DISPATCH 当前用 LOCAL 渠道验证 dispatch/s 和 ACK 路径；外部 API/SFTP/OSS 的 ACK 延迟需要另接 mock endpoint 或真实测试端点。

---

## 2026-05-05 — 四类 Worker 阶梯加压

### 目的

在四类 worker 成功基线之后，继续做 `1 / 2 / 4 / 8 / 16` 阶梯加压，观察本地单 worker 部署的拐点。为避免并发重复数据干扰，本轮已将 payload 改为 Gatling `#{traceId}` 唯一化：

- IMPORT：每个 VU 的 `customerNo` 不同。
- EXPORT：每个 VU 的 `fileName/targetPath` 不同。
- DISPATCH：每个 VU 的 `externalRequestId` 不同。
- PROCESS：每个 VU 的 `batchKey` 不同。

### 命令

```bash
STEPS_CSV=1,2,4 RAMP_SECONDS=3 IMPORT_PROFILE=medium \
  WAIT_TERMINAL_TIMEOUT_SECONDS=240 MAX_ERROR_PCT=50 \
  bash load-tests/scripts/run-worker-stress-tests.sh

STEPS_CSV=8,16 RAMP_SECONDS=5 IMPORT_PROFILE=medium \
  WAIT_TERMINAL_TIMEOUT_SECONDS=360 MAX_ERROR_PCT=50 \
  bash load-tests/scripts/run-worker-stress-tests.sh
```

报告：

- `load-tests/target/worker-stress-report-ltw-stress-20260505105017.md`
- `load-tests/target/worker-stress-report-ltw-stress-20260505105443.md`

### 1 / 2 / 4 阶梯结果

| 并发 | IMPORT | EXPORT | DISPATCH | PROCESS |
|---:|---|---|---|---|
| 1 | 1/1 成功，avg 3.174s | 1/1 成功，avg 1.230s | 1/1 成功，avg 1.498s | 1/1 成功，avg 5.255s |
| 2 | 2/2 成功，avg 21.944s | 2/2 成功，avg 11.282s | 2/2 成功，avg 0.916s | 2/2 成功，avg 1.668s |
| 4 | 4/4 成功，avg 1.349s | 4/4 成功，avg 3.672s | 4/4 成功，avg 5.695s | 4/4 成功，avg 1.373s |

### 8 / 16 阶梯结果

| 并发 | IMPORT | EXPORT | DISPATCH | PROCESS |
|---:|---|---|---|---|
| 8 | 7/8 成功，1 个停留 `CREATED` 超过 360s；avg 1.930s（仅终态样本） | 8/8 成功，avg 4.478s，p95 13.362s | 8/8 成功，avg 21.249s，p95 33.971s | 8/8 成功，avg 11.578s，p95 12.609s |
| 16 | 16/16 成功，avg 18.279s，p95 19.909s | 16/16 成功，avg 8.198s，p95 11.503s | 16/16 成功，avg 4.016s，p95 11.411s | 10/16 成功，6 个停留 `CREATED` 超过 360s |

### 判断

1. **HTTP launch 入口仍未到极限**：`8/16` 阶梯 Gatling 请求失败率都是 0%，p95 仍在 1s 内；瓶颈不在 trigger HTTP 接口。
2. **调度 / 派发出现非稳定滞留**：IMPORT 在并发 8 出现 1 个实例长期 `CREATED`，而 worker load 回落到 0；这不是 worker 正在跑满，而是实例到 task/partition 派发链路有滞留。
3. **PROCESS 在并发 16 达到本地明显拐点**：10/16 成功，6 个实例超过 360s 仍未进入终态；这是本轮最明确的 worker 类型瓶颈信号。
4. **EXPORT / DISPATCH 在本地并发 16 仍能完成**：但 DISPATCH 使用的是 LOCAL 渠道，不能代表真实外部 ACK 服务上限。
5. **本地可建议容量边界**：四类同时看，`并发 4` 是当前最稳的回归压测档位；`并发 8` 开始出现调度滞留风险；`并发 16` 对 PROCESS 不可接受。

### 后续建议

1. 单独针对 PROCESS 做 `8 → 10 → 12 → 14` 细粒度阶梯，定位更精确的拐点。
2. 给 `CREATED` 长期滞留补调度 sampler，采 `job_instance/job_partition/job_task` 生命周期转换耗时，而不只看终态。
3. DISPATCH 下一步换 API mock endpoint，记录真正的 `dispatch/s` 与 ACK 延迟。
4. 如果目标是系统总吞吐极限，需要并行压四类 worker，而不是当前逐类顺序压测。
