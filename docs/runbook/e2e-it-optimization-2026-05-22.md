# E2E / IT 提速优化日志(2026-05-22)

> 历次轮次的措施 + 实测数据 + 环境约束。下次接手优化的人不用从零摸索。

## TL;DR

- **本地全 reactor verify**:`mvn verify` 串行 ~25 min(已优化,基线是 ~30 min)
- **CI(full-ci-gate)**:7 job 全并发,**wall-clock 30 min → 6:32**(-77%,run `26263116015`)
- 唯一不可压的:`WorkerProcessRestartRecoveryE2eIT`(122s,@DirtiesContext 强 reload)

### 2026-05-22 末轮 CI 实测(run 26263116015,wall-clock 6:32)

| Job | 耗时 | 内容 |
|---|---|---|
| security-scan | 1:11 ❌ | gitleaks pre-existing,与本轮优化无关 |
| static-checks | 2:02 ✅ | PMD + Spotless + dep-boundary |
| unit-it-a | 4:43 ✅ | common + trigger + orchestrator |
| unit-it-b | 6:22 ✅ | worker-* + console-api(瓶颈) |
| e2e-shard 1 | 6:03 ✅ | LPT 最长 shard |
| e2e-shard 2 | 5:45 ✅ | |
| e2e-shard 3 | 6:00 ✅ | |
| e2e-shard 4 | 6:27 ✅ | |

下一轮瓶颈:`unit-it-b` 6:22(若要再砍,拆 worker-* 与 console-api 成 2 job,可降到 ~4 min)。

## 措施分层

### Layer 1 落地:E2E 自身(commit `5a1a04c6`)

| 改动 | 文件 | 收益 |
|---|---|---|
| `pollInterval(5s/2s) → 200ms`(9 处 Awaitility) | 7 个 `*E2eIT.java` | -20%~-48% on 改过的 IT |
| MinIO + Redis `.withReuse(true)` | `AbstractIntegrationTest.java` | -5~10s/run(跨 JVM 复用容器) |

**回滚**(实测撞回归):
- PG `.withReuse(true)`:`outbox_event` 跨 run 残留 → MultiTenant 隔离失败
- Kafka `.withReuse(true)`:`stopKafkaForFaultInjection` 在 reuse 容器禁用
- `AbstractIntegrationTest` 全局 `@BeforeEach` truncate:误清测试自有 fixture event

### Layer 2 落地:CI 拆分 + 并发(commit `<待补>`)

CI 同步并发 2 个杠杆:

1. **拆 job**(`full-ci-gate.yml`):
   - `unit-it` job:跑单元 + IT(`run-full-regression.sh --skip-it-suite`)+ security scans
   - `e2e-shard` job:专门跑 e2e module(分 4 shard)
2. **e2e shard matrix**:22 个 `*E2eIT` 按 LPT 均衡分 4 组,GitHub Actions matrix 并发跑

#### Shard 分组(LPT 算法,baseline 数据)

| Shard | 总耗时 | 含 IT |
|---|---|---|
| 1 | 338s | WorkerProcessRestartRecovery, ExportFailurePipeline, WorkerDrain, ProcessPipeline, OutboxForwarderRetry |
| 2 | 292s | ImportFailure, FullChainTenantPropagation, ImportFailurePipeline, ExportPipeline, TriggerAsyncLaunchFullChain |
| 3 | 334s | DeadLetterApprovalReplay, DispatchFailurePipeline, MultiTenantConcurrent, ProcessFailurePipeline, OutboxForwarder, SensorWaitFixture |
| 4 | 334s | ExportStorageFailure, DispatchPipeline, ExportContentVerification, ImportPipeline, DedupJobLaunch, OutboxPollMarginsYaml |

理论 wall-clock(并发):`max(unit-it ~14min, max(shard) ~8min) + setup 1-2min` ≈ **15-17 min**。

### Layer 3 落地:工程治理

| 改动 | commit |
|---|---|
| `cleanup_orphan_testcontainers()`:本地 + CI 测前清「无 reuse-hash 的孤儿容器」 | `62190647` |
| `e2e-parallel` profile(本地默认串行 / CI `-De2e.parallel=true` 启 forkCount=2) | `3c3e3cf3`(后被 shard 方案替代但保留) |

## 实测数据快照(2026-05-22 第 2 次,本地干净环境)

| 阶段 | 耗时 | 内容 |
|---|---|---|
| batch-common | 38s | 单测 |
| batch-trigger | 1:51 | 单测+IT |
| batch-orchestrator | 3:28 | 单测+IT |
| batch-worker-core | 30s | 单测 |
| batch-worker-import/export/process/dispatch | ~1min/each | 单测+IT |
| batch-console-api | 3:08 | 单测+IT |
| batch-e2e-tests | 21:37 | 22 个 E2E |
| **合计** | **~39 min** | mvn verify |

E2E 22 个完整明细(降序):

| TEST | new | baseline | 减时 |
|---|---:|---:|---:|
| WorkerProcessRestartRecovery | 122.7s | 130.5s | -6% |
| ImportFailure | 72.5s | 143.0s | **-49%** |
| DeadLetterApprovalReplay | 65.0s | 86.1s | -24% |
| ExportStorageFailure | 61.1s | 91.2s | -33% |
| DispatchPipeline | 60.4s | 68.1s | -11% |
| DispatchFailurePipeline | 60.4s | 65.0s | -7% |
| FullChainTenantPropagation | 57.8s | 67.3s | -14% |
| ExportContentVerification | 57.2s | 144.2s¹ | -60%¹ |
| MultiTenantConcurrent | 56.8s | 74.2s | -23% |
| ImportFailurePipeline | 56.4s | 64.8s | -12% |
| ExportFailurePipeline | 55.7s | 60.9s | -8% |
| WorkerDrain | 55.5s | 59.6s | -6% |
| ImportPipeline | 55.1s | 107.1s | **-48%** |
| ProcessFailurePipeline | 54.0s | 60.1s | -10% |
| ExportPipeline | 54.0s | 61.4s | -12% |
| ProcessPipeline | 53.7s | 68.5s | -21% |
| DedupJobLaunch | 53.6s | 62.2s | -13% |
| OutboxForwarder | 53.4s | 85.1s | -37% |
| TriggerAsyncLaunchFullChain | 51.0s | 68.0s | -24% |
| OutboxForwarderRetry | 50.4s | 89.2s | **-43%** |
| OutboxPollMarginsYaml | 46.5s | 54.5s | -14% |
| SensorWaitFixture | 44.1s | 80.5s | **-45%** |
| **合计** | **1297s** | **1791s** | **-28%** |

¹ baseline 该 IT 是 PG 容器启动失败的虚高,不是真正 144s。

## 环境前提 / 约束

### 本地(macOS)
- **JVM**:Java 25.0.2 (Homebrew openjdk)
- **Docker Desktop 内存**:7.75 GB
- **必须**:`~/.testcontainers.properties` 含 `testcontainers.reuse.enable=true`(MinIO/Redis reuse 才生效)
- **本地不推荐 forkCount>1**:7.75GB 内存 + docker-compose dev 服务(若开)= 约 4-6GB 可用,跑 2 fork 必 OOM

### CI(GitHub Actions ubuntu-latest)
- **每 runner 内存**:16 GB
- **每 job 独立 runner**,docker daemon 各自隔离(不会撞资源)
- **限制**:GitHub Actions 免费版 concurrent runner 上限(20 个 standard runner),5 job 并发够用
- **m2 cache**:每 job 重新构建(可选 actions/cache 加速,本工程未加)

### 已知失败模式

| 症状 | 原因 | 解决 |
|---|---|---|
| `MultiTenantConcurrent.outboxEventsAreIsolatedBetweenTenants` 期望 0L 实际 1L | PG reuse 让 outbox_event 跨 run 残留 | 已回滚 PG reuse |
| `OutboxPublishCircuitBreakerKafkaFailureIT` 期望失败次数 0 | Kafka reuse 让 `stopKafka()` 行为变 | 已回滚 Kafka reuse |
| `ImportFailure` / `OutboxForwarderRetry` 测试 timeout 等不到事件 | `@BeforeEach` 全局 truncate 误清 fixture | 已撤回全局 truncate |
| docker daemon OOM,IT 集体超时 | 反复 mvn 中断 + Ryuk 没机会清,孤儿容器堆积 | `cleanup_orphan_testcontainers()` 测前清 |
| `OutboxPublishCircuitBreakerKafkaFailureIT` 在 P2 之前的 baseline 偶现 fail(1L vs 0L 类) | 同 outbox 残留 | 同上 |

## 操作手册

### 本地全量跑(干净环境)
```bash
# 1. 关 docker-compose dev 服务(释放 1.5GB)
docker compose down

# 2. 清孤儿 testcontainer(由 scripts/local/run-tests.sh 自动调,可手动)
docker ps -q --filter "label=org.testcontainers=true" | xargs -r docker rm -f

# 3. 跑
mvn verify  # 串行,~25 min
# 或
bash scripts/local/run-tests.sh all
```

### 本地只跑 E2E
```bash
bash scripts/local/run-tests.sh e2e   # ~22 min
# 自动调 cleanup_orphan_testcontainers
```

### CI 上的并发跑(自动触发)
- `push main`:`full-ci-gate` workflow 自动跑
- `unit-it` job + `e2e-shard×4` matrix 并发,wall-clock ~15-17 min

### 下次扩容(若再要提速)

| 路径 | 减时 | 工程量 |
|---|---|---|
| e2e shard 4→8 | ~3-4 min | 低(改 matrix 列表)|
| `WorkerProcessRestartRecovery` 独立成 1 个 shard(避免 max) | -1~2 min | 低 |
| reactor `mvn -T 2`(unit-it 并行) | -3~5 min | 中(pom 注释说撞端口竞态,需实测)|
| 改测试架构:每 IT 独立 PG schema | -10 min | **高**(改 AbstractIntegrationTest + Flyway)|

## Commit 历史

| Hash | 内容 |
|---|---|
| `5a1a04c6` | E2E P3 pollInterval + MinIO/Redis withReuse,**E2E 30→25min** |
| `3c3e3cf3` | e2e-parallel profile(已被 shard 替代,保留兼容) |
| `16373b1e` | spotless javadoc 折行修复 |
| `eb5a56f8` | AlertRoutingSaveRequest @NotBlank + 测试 mock 补字段 |
| `62190647` | `cleanup_orphan_testcontainers()` 本地+CI 测前清 |
| `<待补>`   | CI `full-ci-gate` 拆 unit-it + e2e-shard×4 |
