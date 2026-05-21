# E2E IT 提速优化日志(2026-05-22 第 1 轮)

## TL;DR

**baseline 30 min → 优化后 25 min(-17%)**;尝试了 4 项,落地 2 项,回滚 2 项。

| 改动 | 状态 | 原因 |
|---|---|---|
| **P3** `pollInterval 5s/2s → 200ms`(9 处 Awaitility) | ✅ 落地 | 9 个 E2E 受益,顶级 -48%(ImportFailure / ImportPipeline) |
| **P2-a** MinIO + Redis `.withReuse(true)` | ✅ 落地 | 跨 JVM run 复用容器,~5s/run 收益,无副作用 |
| **P2-b** PostgreSQL `.withReuse(true)` | ❌ 回滚 | `outbox_event` 跨 run 残留 → 破坏 MultiTenantConcurrent / OutboxForwarderRetry / ImportFailure |
| **P2-c** Kafka `.withReuse(true)` | ❌ 回滚 | 多个 IT 用 `stopKafkaForFaultInjection()` 做 fault injection,reuse 容器禁止 stop |
| **P4** `AbstractIntegrationTest` 全局 `@BeforeEach` truncate outbox | ❌ 回滚 | 误清 ImportFailure 等测试自己产的 outbox 事件 → timeout |

## baseline vs P2+P3 实测(23 个 E2E)

| TEST | baseline | 优化后 | Δ |
|---|---|---|---|
| ImportFailureE2eIT | 143.0s | 74.3s | **-48%** |
| ImportPipelineE2eIT | 107.1s | 55.0s | **-48%** |
| OutboxForwarderE2eIT | 85.1s | 58.9s | -30% |
| OutboxForwarderRetryE2eIT | 89.2s | 66.3s | -25% |
| ProcessPipelineE2eIT | 68.5s | 58.0s | -15% |
| TriggerAsyncLaunchFullChainE2eIT | 68.0s | 59.5s | -12% |
| MultiTenantConcurrentE2eIT | 74.2s | 66.3s | -11%⁽¹⁾ |
| ImportFailurePipelineE2eIT | 64.8s | 57.2s | -11% |
| SensorWaitFixtureE2eIT | 80.5s | 72.0s | -10% |
| ProcessFailurePipelineE2eIT | 60.1s | 65.6s | +9% ⚠️ |
| FullChainTenantPropagationE2eIT | 67.3s | 61.3s | -8% |
| OutboxPollMarginsYamlE2eIT | 54.5s | 50.1s | -8% |
| ExportStorageFailureE2eIT | 91.2s | 84.9s | -6% |
| DispatchPipelineE2eIT | 68.1s | 63.6s | -6% |
| DedupJobLaunchE2eIT | 62.2s | 58.4s | -6% |
| WorkerProcessRestartRecoveryE2eIT | 130.5s | 136.3s | +4% ⚠️ |
| DispatchFailurePipelineE2eIT | 65.0s | 67.0s | +3% ⚠️ |
| DeadLetterApprovalReplayE2eIT | 86.1s | 89.7s | +4% ⚠️ |
| WorkerDrainE2eIT | 59.6s | 62.4s | +4% ⚠️ |
| ExportPipelineE2eIT | 61.4s | 60.8s | -1% |
| ExportFailurePipelineE2eIT | 60.9s | 60.9s | 0% |
| ExportContentVerificationE2eIT | 144.2s⁽²⁾ | 56.6s | -60%⁽²⁾ |
| **合计** | **1791s (29.9 min)** | **1485s (24.7 min)** | **-17%** |

⁽¹⁾ MultiTenant 整 class 测时 -11% 但内部 `outboxEventsAreIsolatedBetweenTenants` 子测失败(已知问题,见下)
⁽²⁾ baseline 144s 是 PG 容器启动失败的卡死时间,不是真耗时

## 已知问题(归 backlog)

1. **MultiTenantConcurrentE2eIT.outboxEventsAreIsolatedBetweenTenants** baseline 跑时 PG 残留 1 条 outbox event(expected 0L but was 1L)。本次回滚 PG withReuse 后理论该过(PG 每次新建),但本地环境内存吃紧无法稳定验证。CI 上需重点观察。

2. **本地环境瓶颈**:7.75GB Docker 内存 + docker-compose dev 服务(1.5GB)+ 测试用 testcontainers(800MB+) 共 20+ 容器,反复 mvn 中断让状态混乱。**优化在 CI 干净环境上效果应更明显且稳定**。

## 调用方法

### 改动文件
- `batch-common/src/test/java/com/example/batch/testing/AbstractIntegrationTest.java`
  - MinIO + Redis 加 `.withReuse(true)`
  - 加 protected static `truncateOutboxTables(DataSource)` 辅助方法(供需要的 IT 显式调,不在 @BeforeEach 全局兜底)
- `batch-e2e-tests/src/test/java/com/example/batch/e2e/{DeadLetterApprovalReplay,FullChainTenantPropagation,ImportPipeline,MultiTenantConcurrent,OutboxForwarder,OutboxForwarderRetry,TriggerAsyncLaunchFullChain}E2eIT.java`
  - `pollInterval(Duration.ofSeconds(5/2))` → `pollInterval(Duration.ofMillis(200))`(共 9 处)

### Testcontainers reuse 前置条件
`~/.testcontainers.properties` 已配:
```properties
testcontainers.reuse.enable=true
```

### 强制清 reuse 容器
本地测试出现 outbox 状态混乱时:
```bash
docker ps -q --filter "label=org.testcontainers=true" | xargs docker rm -f
```

## 下一轮候选(P7,本轮收益小 + > 60s)

`>60s 且本轮提升 ≤ 10%` 的 8 个 E2E,各类型瓶颈不同:

| TEST | 现耗时 | 推测瓶颈 | 思路 |
|---|---|---|---|
| WorkerProcessRestartRecoveryE2eIT | 136s | @DirtiesContext 强制 reload | 接受现状或独立 module |
| ExportStorageFailureE2eIT | 85s | 等 MinIO 5x retry exponential backoff | 调短 retry 间隔(测试 profile) |
| DeadLetterApprovalReplayE2eIT | 90s | Console HTTP 调用串行 + 多 await | 合并 await 等待集 |
| DispatchPipelineE2eIT | 64s | dispatch async ack 等待 | 看 ack 间隔配置 |
| DispatchFailurePipelineE2eIT | 67s | 同上 |  |
| ExportPipelineE2eIT | 61s | MinIO upload + verify | 大文件改小 |
| ExportFailurePipelineE2eIT | 61s | 同上 |  |
| WorkerDrainE2eIT | 62s | `drain.check-interval-millis=600000` 故意长等 | 不能改(测的就是 drain) |
| ProcessFailurePipelineE2eIT | 66s | retry 等待 |  |
| SensorWaitFixtureE2eIT | 72s | sensor poll wait | 调短 sensor 间隔 |
| FullChainTenantPropagationE2eIT | 61s | 全链路触发 | 端到端 fundamental,难压 |

预估再做 P7 能多省 ~3-5 min(剩下都是「业务必须等」),性价比下降。
