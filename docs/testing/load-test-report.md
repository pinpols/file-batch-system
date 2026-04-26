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
