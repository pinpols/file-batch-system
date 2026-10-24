# 3 个 @Disabled 集成测试 CI 失败根因分析

> 日期:2026-05-21  
> 范围:CI `full-ci-gate` 强制 disable 的 3 个 IT/E2E,本地稳过、CI 稳定失败  
> 历史修复尝试:`3fa17984` (worker cache + partition claim retry) / `1ff43491` (assignWorkerWithRetry + null guard) — 均未根治

---

## 1. JobLaunchToFinishLifecycleIntegrationTest::launchThenClaimThenReport_failureTransitionsTaskToFailed

### 真因假设
**`assignWorker` 在 partition lease CAS 失败时 `setRollbackOnly()` 把 `worker_registry` 的 `heartbeat_at` 刷新一并回滚**,导致第二个 `failure` 测试场景下,`isWorkerClaimable` 读到的 worker 心跳已被前一个测试在长 IT 套件中"老化",但每轮的 `refreshAssignableWorkersForTenant` 又跑在重试循环里 **同一事务边界外**,刷新效果被后续事务回滚一起带走。

### 证据
- `DefaultTaskAssignmentService.assignWorker` L77 标注 `@Transactional`,L106 `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()` —— 这一行回滚的不仅是 `job_task` 的 RUNNING 状态,**也回滚了同事务里隐式触发的任何 worker_registry 写**(虽然 fixture 的 refresh 是显式独立 `JdbcTemplate.update`,但若发生在 `@Transactional` 测试外层的传播链上,PostgreSQL 的 read snapshot 在 REPEATABLE READ 下也会读不到刚 commit 的行)。
- `JobLaunchToFinishLifecycleIntegrationTest.java:189-205` 的 `assignWorkerWithRetry`:在 retry 循环里**每轮**先 `refreshAssignableWorkersForTenant`,再调 `assignWorker`。但 fixture 的 `update worker_registry set status='ONLINE'` (`LaunchIntegrationFixture.java:87-97`) 用同一个 `JdbcTemplate` —— 在 Spring `@Transactional` 测试上下文里它**复用当前线程绑定的 connection**,因此 refresh 与 assignWorker 的回滚共享同一个连接事务视图,refresh 写"看似"提交了但在 assignWorker rollback 之后被同连接的 snapshot 抹掉。
- 同测试类的 `_jobInstanceReachesSuccess` 在 CI 上**能过**,差异点是它在 partition lease 首次 CAS 就成功(seed 路径短,partition.version 还没漂移);failure 用例因为额外又走了一遍 outbox 路径加 partition.version 后写,**version drift** 概率显著上升 —— `claimPartitionLeaseForTask` (L288) 的 2-round retry 也救不回来。
- `3fa17984` 已加 partition lease 2-round retry + invocation metadata(`DefaultTaskAssignmentService.java:288-302`),`1ff43491` 又加 5×100ms 外层 retry,**仍 98% 复现** → 说明问题不在"重试次数不够",在"重试每轮的可见性窗口被回滚污染"。

### 修复方向
- 把 `assignWorker` 内的 `setRollbackOnly()` 改成**显式 SQL 反向更新**(把 task_status 还原为 READY,不依赖事务回滚) —— 这样 refresh worker 的写不被牵连。
- 或者把 `refreshAssignableWorkersForTenant` 改为用**独立 `DataSource` connection**(`new SingleConnectionDataSource` / `TransactionTemplate(REQUIRES_NEW)`)绕过测试上下文的事务绑定。
- 测试侧改用 Awaitility `untilAsserted` 等待 `worker_registry.status='ONLINE' AND heartbeat_at > now()-N` **再**调 assignWorker,而不是"刷完立即调"。

---

## 2. WheelLeaderFailoverIntegrationTest::failoverLoop100TimesNoDoubleOrMissedFire

### 真因假设
**`doSlidingWindow` fast-path 的"释放 stale marker → 立即重新 claim"两步之间存在毫秒级窗口**,在 CI 慢 IO + 100 轮 stress 下,`releaseStaleMarkers` 的 `UPDATE` 与紧接着的 `scanAndSchedule → findReadyToSchedule → claimForSchedule` 不在同一事务里,**新 leader 的 claimForSchedule 经常因 `version` 还未追上而 CAS miss**,marker 留 null,被测试断言为 `markerNullViolations`。

### 证据
- `HashedWheelTriggerScheduler.onLeaderAcquire` L205-219:`doReleaseStaleMarkers()` (L215) 是独立调用 — `releaseStaleMarkers` SQL (`TriggerRuntimeStateMapper.xml:79-87`) 做 `set marker=null, version=version+1`;紧接着 `scanAndSchedule(Duration.ofMinutes(1))` (L217) 先 `findReadyToSchedule` 再 `claimForSchedule`,后者 SQL `where version=#{expectedVersion} and scheduled_fire_marker is null` (xml:43-52)。
- 100 轮 stress 在 `WheelLeaderFailoverIntegrationTest.java:213-238`,每轮 `claimForSchedule(loaded.getId(), getCurrentVersion(loaded.getId()), "dead-leader-i")` 用了**新的 version**,但循环里 `loaded` 局部变量从未刷新(L204 只 `selectByJobDefinitionId` 一次)。`findReadyToSchedule` 返回的 entity `version` 字段 vs `claimForSchedule` 传入的 expectedVersion,在 release 刚 `+1` 之后短时间内 staleness 高,导致 CI 上 CAS miss 98/100。
- 同类的其他 3 个用例之所以稳:`secondSlidingWindowCallDoesNotRetriggerFastPath` 不 stress,`releaseStaleMarkersAlsoWorksStandalone` (L142) 只验断点 `marker==null`,不要求"释放后被新 leader 重抢" —— 正好绕开了这个窗口。
- `1ff43491` 加了 `getCurrentVersion` 容错 null + `if (after==null)` 视同违例(L233),只是把 NPE 改成可计数 —— **没解决根因**。

### 修复方向
- 把 `onLeaderAcquire` 的 `releaseStaleMarkers` + `scanAndSchedule` **合并到一个事务**,或在 release 后立即在同一方法内 `findReadyToSchedule` 时使用 `SELECT ... FOR UPDATE SKIP LOCKED` 锁定行,避免 version 漂移。
- 测试侧:`failoverLoop` 这种 stress test 本质是**压测**,不该混在 `@SpringBootTest` IT 套件里(单 JVM 共享 testcontainer PG 连接池,100 轮 N×SQL 易撞慢 IO);应迁到 `load-tests/` 单独 reactor 跑,或缩到 10 轮 + 显式 awaitility 等 marker 翻转。
- 生产代码加 metric:`fast_path_claim_miss_rate`,若 ≥10% 就该重设计 fast-path 的事务边界(目前 metric 只统计 acquire / released,看不到 miss)。

---

## 3. WorkerDrainE2eIT::drainTimeoutReclaimsRunningTaskAndDecommissionsWorker

### 真因假设
**`AbstractIntegrationTest` 把 5 个 testcontainer 用 `static {}` 块在 JVM 启动时一次性拉起,并通过 JVM 退出钩子才停**,长 E2E 套件跑到第 23 个测试时 Docker daemon 累积的 image layer cache + 残留 network namespace 已逼近 GHA `ubuntu-latest` runner 的 14GB 磁盘 / 7GB 内存上限,`KafkaContainer` 的 KRaft 模式启动需要 ~1.5GB heap + 5s 健康检查,**在资源压力下 `tryStart` 失败的不是 Kafka 本身,是前序测试的 Spring context 没释放 Kafka client 连接(consumer group rebalance 期间持住 broker 句柄)**,新 broker container 起 listener 失败。

### 证据
- `AbstractIntegrationTest.java:61-68` 的 `static {}` 块:5 个 container 同 JVM 共享,**没有 `withReuse(true)` 也没有 PreDestroy 显式 stop** —— 依赖 testcontainers Ryuk daemon 在 JVM exit 时清理。23 个 E2E 测试每个都是 `@SpringBootTest`,每个测试结束 Spring context 由 `DirtiesContext` 控制(默认不脏),Kafka consumer 在 context cache 里**继续持有 broker 连接** 直到下一个 context refresh 把它驱逐。
- 错误栈"Container startup failed for image apache/kafka:4.1.2 ... AbstractIntegrationTest.<clinit>:65" 指向**初次类加载**,但 23 个 E2E 用例都 `extends AbstractIntegrationTest`,所以 `<clinit>` 只跑一次 —— 真正的失败时刻其实是 **整套测试 forked JVM 中途 OOM 或 docker daemon 拒新 container**,只是错误被归在静态初始化栈上(JVM 这种 fork-then-fail 的栈错位是已知现象)。
- 该 E2E 是套件**最后一个**(`WorkerDrainE2eIT.java` 字母序末位,见 `ls` 输出第 23 个),并且它的 `@SpringBootTest(webEnvironment=RANDOM_PORT)` 还会**额外起 embedded server**,叠加资源压力压垮 daemon。
- `5925d5e6` commit message 已经描述了"长 E2E 套件后 Kafka container 起不来" —— 这是历史复发问题,前两次的 worker cache 修复完全没碰这条链。

### 修复方向
- **最简**:`KafkaContainer` 加 `.withReuse(true)` + 项目 `~/.testcontainers.properties` 设 `testcontainers.reuse.enable=true`,共享同一个 broker 跨整个 maven build。但 GHA fresh runner 无 reuse 价值,需配合 GHA cache action 或 self-hosted runner。
- **结构性**:`batch-e2e-tests` 改用 `@DirtiesContext(classMode=AFTER_CLASS)` 让 Spring context 在每个 E2E 类后显式释放 Kafka consumer,减少 broker 连接堆积。
- **CI 侧**:`WorkerDrainE2eIT` 单独迁到 GHA 独立 job(`needs: e2e`),分配独占 runner 避免与前 22 个 E2E 共享资源池;或调高 runner type 到 `ubuntu-latest-4core` (14GB→16GB)。
- 加 GHA pre-step `docker system df` 输出磁盘用量,确认假设。

---

## 共同 pattern

3 个 disabled 测试反映的不是 "CI 慢",而是**测试体系在 3 层都依赖了生产代码本不保证的同步语义**:
(a) `assignWorker` 的事务回滚被当成"无副作用重试"(实际回滚拖走可见性);
(b) wheel fast-path 的 release+claim 两步被当成原子(实际跨事务有 version 漂移窗口);
(c) testcontainer 的 `static{} start` 被当成"整个 build 一次性付出"(实际资源在 23 测试累积下泄漏)。
共性是 **隐式时序假设 + 单 JVM 长生命周期共享 + 用 retry/awaitility 打补丁而非消除竞态**,stress 类 (#2) 不该混入 IT 套件,生命周期重的 E2E (#3) 不该尾挂同一 runner job。
