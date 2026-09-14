# 重任务五项保障验证报告（2026-09-14）

## 1. 范围

本轮验证覆盖：全局活跃作业准入、租户/队列派发 QPS、资源画像路由、稳定池与实例身份、资源池 Kafka
隔离、下游 Dispatch 渠道健康准入、WAITING 重派字段保真、池路由 claim CAS，以及容量画像墙钟口径。

本报告是当前分支的本地证据，不替代合并后的 Full Gate 和目标环境容量测试。

## 2. 实现结果

| 项 | 结果 | 主要证据 |
|---|---|---|
| 全局活跃作业硬上限 | 完成 | PostgreSQL transaction advisory lock + `countActiveAll`；生产启动守卫 |
| 租户/队列 QPS | 完成 | Redis Bucket4j 每秒共享桶；动态阈值独立签名；内部派发后端故障 fail-closed |
| CPU/内存/IO 资源池 | 完成 | `resourceProfile` 路由、稳定池代码、Pod UID 实例 ID、`DIRECT_ONLY` topic |
| WAITING 重派契约 | 完成 | `resourceProfile` 和下游渠道写入 `input_snapshot`，重派恢复同一请求 |
| 具体实例 claim | 完成 | pool code 只用于路由，claim CAS 后 task/partition 写实际实例 ID |
| Dispatch 下游健康 | 完成 | `file_channel_health != HEALTHY` 时返回 DEFER |
| 长任务续租/取消/checkpoint | 复用既有能力并复核 | lease、timeout、cancel、reclaim、checkpoint 单测/IT 与历史 sim 证据 |
| 容量画像口径 | 完成 | 新增 `wallClockDurationMs`，records/s 与 MB/s 改用墙钟时间 |

## 3. 本地验证

### 3.1 定向单元测试

```bash
./mvnw -ntp -pl batch-worker/core,batch-orchestrator -am -DskipITs \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=AbstractTaskConsumerTest,AbstractWorkerLoopTest,\
DefaultPartitionDispatchServiceTest,DefaultPartitionLifecycleServiceTest,\
WaitingPartitionDispatchSchedulerRequestTest,DefaultTaskAssignmentServiceTest,\
GlobalJobAdmissionGuardTest,QuotaExceededStrategyLimiterTest,WaitingPartitionDispatcherTest,\
DefaultWorkerSelectorTest,DefaultResourceSchedulerTest,DefaultDispatchAdmissionLimiterTest,\
DispatchChannelAdmissionGuardTest,ResourceAdmissionStartupGuardTest,WorkerRegistryCacheTest,\
WorkerControllerTest,CapacityProfileServiceTest test
```

结果：**PASS，193 tests，0 failure，0 error，0 skipped**。

覆盖的关键反例包括：

- 全局上限已满时普通作业和 DAG 父实例不错误进入 RUNNING；
- WAITING 父实例释放前重新取得全局容量，已有 DAG 后续节点不重复占槽；
- Redis 限流后端故障时内部派发不 fail-open；
- 资源画像不匹配时不回退到普通池；
- `DIRECT_ONLY` 不匹配 base、租户或其它池 topic，特殊字符与生产者清洗规则一致；
- 稳定池内实例可 claim，其他池实例不能越权 claim；
- WAITING 重派不丢失资源画像和渠道代码；
- Dispatch 单渠道、多渠道和 fan-out 渠道非健康时 DEFER，其他 Worker 类型不受影响；
- 资源队列令牌先于租户令牌消费，队列过载不会耗尽同租户其他队列的共享预算。

### 3.2 受影响模块编译

```bash
./mvnw -ntp -pl batch-common,batch-console-api,batch-orchestrator,batch-trigger,\
batch-worker/core,batch-worker/import,batch-worker/export,batch-worker/process,\
batch-worker/dispatch,batch-worker/atomic,batch-worker-sdk,batch-worker-sdk-spring-boot-starter,\
batch-worker-sdk-testkit -am -DskipTests package
```

结果：**PASS，13 个受影响模块及其依赖完成编译和打包**。

### 3.3 PostgreSQL 17 集成验证

在 Testcontainers PostgreSQL 17 上执行以下 5 个测试类，共 11 个测试：

- `ConcurrentTaskClaimIntegrationTest`；
- `GlobalJobAdmissionConcurrencyIntegrationTest`；
- `DownstreamAdmissionMapperIntegrationTest`；
- `CapacityProfileMapperIntegrationTest`；
- `LocalFlywayPlatformMigrationsIntegrationTest`。

结果：**PASS，11 tests，0 failure，0 error，0 skipped**。Flyway 从基线执行到 V209；验证了同池多实例
claim CAS、全局准入锁并发边界、下游健康查询、墙钟容量聚合和新增列迁移兼容性。

### 3.4 Helm 与配置契约

| 验证 | 结果 |
|---|---|
| `helm lint helm/batch-platform` | PASS |
| `values-heavy-worker-pools.yaml` 渲染 | PASS；CPU/内存池、`DIRECT_ONLY`、并发参数和 2 个 PDB 均符合预期 |
| 标准 worker PDB 渲染 | PASS；包含此前遗漏的 Process Worker |
| `globalMaxRunningJobs=0` 显式渲染 | PASS；Helm 不再把合法的本地零值覆盖为默认值 |
| `check-helm-env-sync.py` | PASS |
| `check-config-defaults-sync.py` | PASS；440 个配置占位符受检 |
| `bash -n scripts/data/init-kafka-topics.sh` | PASS |
| 前端 `api.generated.ts` 重新生成 + `vue-tsc --noEmit` | PASS；`wallClockDurationMs` 类型已同步 |
| `check-migration-safety.sh origin/main` | PASS；V209 经 Squawk 扫描，0 issue |
| `check-db-comment-coverage.sh origin/main` | PASS；新增字段注释完整 |

资源池样例还验证了 `replicaCount: 0`、池级 ServiceAccount、节点选择、affinity、toleration、资源限制、
`maxConcurrentTasks` 和 `executionPoolSize` 的校验与继承规则。通用资源池模板明确拒绝 Atomic Worker。

### 3.5 静态守卫

以下守卫均为 **PASS**：

- production overlay、应用治理和 feature registry；
- Java readability、可读性清单与 Java 文档；
- SQL 边界、Flyway schema/命名、MyBatis generated key 和数据库注释覆盖；
- Console OpenAPI 376 条路由、文档引用/结构、changelog；
- E2E shard 清单 28/28、模块测试覆盖、空值检查；
- `git diff --check`。

全 reactor 另执行 `./mvnw -DskipTests test-compile pmd:check spotless:check -fae`，17 个 Maven 模块
均为 **SUCCESS**，PMD 和 Spotless 无违规。

以上两道迁移守卫在提交后按 `origin/main...HEAD` 重新执行，V209 已被真实纳入差异扫描。合并后的 Full Gate
结果单独由 CI 留档，本报告不预先宣称其通过。

## 4. 复用的长任务证据

| 场景 | 已有证据 |
|---|---|
| Worker 中途退出、lease reclaim、重派 | `docs/verifications/sim-e2e-2026-05-29.md` |
| Import chunk 后 kill、checkpoint 续跑 | `docs/verifications/preprod-worker-p0-pressure-2026-06-08.md` |
| Process kill Worker、PG 断链、幂等重跑 | `docs/verifications/worker-p2-capacity-profile-2026-06-08.md` |
| RUNNING cancel 和 Atomic shell cancel | `docs/verifications/worker-business-scenario-matrix-2026-06-08.md` |
| 五类 Worker 续租/故障覆盖汇总 | `docs/verifications/worker-matrix-verification-2026-06-08.md` |

这些历史报告证明既有长任务机制，不证明本轮资源池改造已经在目标 Kubernetes 集群完成故障注入。本轮真
PG claim IT 和 Full Gate 通过后，只能判定协议与持久层兼容；正式上线仍应按 Runbook 对新资源池执行一次
Pod kill、取消和 checkpoint 演练。

## 5. 未覆盖与结论边界

本轮没有新增 WAITING 全局数量上限/统一 TTL，也没有在任务派发链直接读取 Kafka consumer lag。现有全局
活跃作业上限、租户/队列配额、Worker 负载、派发 QPS 与 Outbox 可以保护执行面，但不能证明任意洪峰下
PG backlog 永不增长。

因此当前结论是：**五项重任务执行保障已落地，代码级主链闭环；pending 存储硬边界和显式 Kafka lag gate
仍按 ADR-042 独立治理。**容量承诺必须使用目标环境的真实数据量、墙钟时间和基础设施指标重新验证。
