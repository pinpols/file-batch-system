# SDK Roadmap H2-2026 — 进度记录

> 日期倒序。每个 phase 收官后追加 5 行 retro:实际 vs plan diff + 经验。
> 维护规则参见主 plan §1 决策 #隐-1。

---

## 2026-05-31 — Phase 1 #SDK-P1-4 metrics() POJO + isHealthy() + consumer 死时上报(Phase 1 收官)

- **范围**:1.6 新增 `SdkClientMetrics` record(10 字段:tenant/worker/started/healthy/inFlight/maxConcurrent/handlerCount/dispatcherFatal/dispatcherDraining/consumerCrashed),`BatchPlatformClient.metrics()` 暴露快照 + `isHealthy()` 复合判定(started && !fatal && !crashed,drain 不算 unhealthy)。1.7 `KafkaTaskConsumer.run()` 在 `catch (Throwable)` 分支置 `crashed=true` + `running=false`,新增 `hasCrashed()` 公开 getter;`TaskDispatcher.isDraining()` 从 package-private 升 `public`(供 BatchPlatformClient 跨包读)。
- **实际 vs plan**:工作量 ~2.5h(plan 估 3h)略低。比 plan 多做:`isHealthy()` 显式定义 drain 期=healthy(语义在 javadoc 钉死)避免后续 caller 误读;`SdkClientMetrics` 是 record(不可变快照)且字段以 javadoc 钉住 Prometheus label 用途。比 plan 少做:**没**真把 fatal/crashed 串到 `System.exit` 或 K8s liveness endpoint —— SDK 不假设宿主进程的 health 暴露方式,只提供 boolean(memory: `feedback_yagni`),由租户自决怎么暴露。
- **环境问题**:JDK 25 仍要本地装(routine 第 4 次),`curl /opt/jdk25-extract`+ cacerts 拷 21,~30s 完成。下次 fire 是否归档下载脚本待 Phase 2 起手时一并评估。
- **测试结果**:`mvn -pl batch-worker-sdk test` 170/170 绿(原 162 + 新 5 `BatchPlatformClientMetricsTest` + 新 3 `KafkaTaskConsumerCrashTest`);`KafkaTaskConsumerCrashTest` 用 Mockito mock `Consumer`(MockConsumer 在 SDK 测试集只用于 rebalance listener 单测,不真跑 run loop 避免 pattern subscribe 兼容性问题)。
- **后续**:Phase 1 全部 PR 已合,Phase 2 (调度上下文下沉) 可起手;但 dual-rollout 纪律要求 ORCH-P2-1 先 merge → 观察 2 周(开发期可缩短到 1d CI green,PR 描述带 `dev-skip-dual-rollout`)再开 SDK-P2-1 / SDK-P2-2。

---

## 2026-05-31 — Phase 1 #SDK-P1-3 HeartbeatScheduler fixed-delay + 异常 message 去 errBody 明文

- **范围**:1.4 `HeartbeatScheduler.start()` 把 `scheduleAtFixedRate` 换成 `scheduleWithFixedDelay`(平台短暂卡顿后不追赶式连发心跳雪崩 orchestrator);1.5 `PlatformHttpClient` 非 2xx 路径把 errBody 从 exception message 拿掉(避免错误链一路 INFO/WARN 时把平台错误 payload + 潜在 token 写满日志),完整 body 只在 DEBUG 级输出 `non-2xx response: status=... url=... body=...`。
- **实际 vs plan**:工作量 ~1.5h(plan 估 2h)略低。比 plan 多做:`HeartbeatScheduler` 加包内可见构造(注入 `ScheduledExecutorService`)让单测能直接 verify `scheduleWithFixedDelay` 被调用 + `scheduleAtFixedRate` 不被调用(否则只能跑实时定时测,慢且 flaky)。比 plan 少做:无,本 PR 严格 1.4 + 1.5,没有顺手并入其它项。
- **环境问题**:JDK 25 在 `/opt` 不存在,重新 `curl https://download.oracle.com/java/25/latest/jdk-25_linux-x64_bin.tar.gz` 207 MB → `tar -xzf` 到 `/opt/jdk25` --strip-components=1,cacerts 从系统 JDK 21 拷过去。**已成 routine,前 3 个 PR 重复 4 次,可考虑把下载脚本归档到 `scripts/local/setup-jdk25.sh` 让 fire 一行起跑**(留作 #SDK-P1-4 顺手做)。
- **测试结果**:`mvn -pl batch-worker-sdk test` 162/162 绿(原 161 + 新 1 `startUsesFixedDelayNotFixedRate`,旧 `non2xxThrows` 改名为 `non2xxThrowsWithoutErrBodyLeak` 并断言 `hasMessageNotContaining("FORBIDDEN", "secret-abc", "body=")` —— 验证敏感字段不再泄露到 message)。
- **后续**:#SDK-P1-4 接力(`BatchPlatformClient.metrics()` POJO + `isHealthy()` boolean + `KafkaTaskConsumer` 异常退出反映到 `isHealthy()=false`),依赖 #SDK-P1-2 已暴露的 `TaskDispatcher.isFatal()` + 本 PR 已稳的 heartbeat 路径;Phase 1 完成后才能开 Phase 2 协议变更(dual-rollout 2 周窗口起算)。

---

## 2026-05-31 — Phase 1 #SDK-P1-2 CLAIM 401/403 fail-fast + 5xx 指数退避

- **范围**:1.2 `TaskDispatcher.claimWithRetry()` 按 HTTP 状态码分类:401/403 → `fatal` flag 置 true + `onMessage` 立刻 drop 后续消息(log ERROR);409 → INFO 给 peer;其它 4xx → WARN 放弃;5xx + 传输 `IOException` → `claimMax5xxRetries` 次指数退避(基准 `claimRetryBaseDelay`,200/400/800ms),耗尽放弃。新 `PlatformHttpException extends IOException` 暴露 `statusCode` + `isAuthError/isConflict/isServerError/isClientError`。`PlatformHttpClient` 非 2xx 改抛新异常(`IOException` 兼容,旧调用方 `catch (IOException)` 仍 OK)。
- **实际 vs plan**:工作量 ~2h(plan 估 2h)持平。比 plan 多做:`BatchPlatformClientConfig` 加 `claimMax5xxRetries`(default 3)+ `claimRetryBaseDelay`(default 200ms),`@Builder(toBuilder = true)` 让测试能 derive 配置;暴露 `TaskDispatcher.isFatal()` 给后续 `BatchPlatformClient.isHealthy()`(#SDK-P1-4)消费。比 plan 少做:**没**真把 fatal 串到 `isHealthy()` 或 `System.exit` —— 只保证后续 dispatch 拒收 + log ERROR,让 K8s liveness / 运维介入(memory: `feedback_yagni`),留 P1-4 / P7-3 收。
- **环境问题**:JDK 25 在 `/opt` 不存在,重做 P0/P1-1 那套:`curl /tmp/jdk25.tgz` → `tar` 到 `/opt/jdk25-extract/jdk-25.0.3` → symlink `/opt/jdk25`,cacerts 从系统 JDK 21 拷过去。容器重建会丢,下次 fire 重复一遍即可。
- **测试结果**:`mvn -pl batch-worker-sdk test` 161/161 绿(原 146 + 新 10 `TaskDispatcherClaimRetryTest` + 5 `PlatformHttpExceptionTest`);旧 `TaskDispatcherTest.claimFailureDoesNotExecuteOrReport` 把裸 `IOException("409")` 换成 `PlatformHttpException(409, ...)` 才准。
- **后续**:#SDK-P1-3 接力(`HeartbeatScheduler` 改 `scheduleWithFixedDelay` + `IOException` message 去 errBody 明文);依赖图(§15.8.A)纯 SDK 链是严格串行,本 PR merged 才能开 P1-3。

---

## 2026-05-31 — Phase 1 #SDK-P1-1 stop() 顺序 + ConsumerRebalanceListener

- **范围**:1.1 `BatchPlatformClient.stop()` 顺序倒置(Kafka 先 wakeup+join → dispatcher drain → heartbeat/lease close → deactivate)、1.3 `KafkaTaskConsumer` 新增 `PauseAwareRebalanceListener` 内部类,`onPartitionsAssigned()` 时若 backpressure 仍激活则 re-pause 新分到的 partition;附 6 测试(3 stop order + 3 rebalance)。
- **实际 vs plan**:工作量 ~3h(plan 估 3h+4h=7h)显著低于预算。比 plan 多做:把 `KafkaConsumer<String, byte[]>` 字段类型 widen 到 `Consumer<String, byte[]>` 让 MockConsumer 能注入;`paused` 字段加 `volatile`。比 plan 少做:Listener 没暴露 metric(`rebalance_count` 等),留给 P1-4 `metrics()` PR 顺手加。
- **环境问题**:JDK 25 在 `/opt` 不存在,curl 从 `download.oracle.com/java/25/latest/jdk-25_linux-x64_bin.tar.gz` 下载到 `/tmp/jdk25`,cacerts 从系统 JDK 21 拷过去解决 SSL 信任。后续 routine 启动复用 `/tmp/jdk25` 即可。
- **测试结果**:`mvn -pl batch-worker-sdk test` 146/146 绿(原 140 + 新 6)。
- **后续**:#SDK-P1-2(CLAIM 401/403 fail-fast)接力;依赖图(§15.8.A)纯 SDK 链是严格串行,本 PR merged 才能开 P1-2。

---

## 2026-05-31 — ⚠️ Phase 0 代码完成,**push 被环境权限拦截**

- **状态**:本地分支 `feature/sdk-phase-0-protocol-foundation` 已 commit 1 个 commit;`git push` 与 `mcp__github__push_files` / `create_branch` 均返回 `403 Permission to pinpols/file-batch-system.git denied to idengzhao`。GitHub MCP 鉴权身份是 `idengzhao`,但目标仓库 `pinpols/file-batch-system` 上没有 write 权限,无法走 routine 自动 push + create PR 流程。
- **退出原因**(对照 supervisor routine「停下条件」):**触红线(pre-push 拒绝)**;需人工介入授权或改鉴权身份。
- **本地交付**:见下方 Phase 0 完成段;`mvn -pl batch-worker-sdk test` 140/140 绿;`SdkWireContractTest` 9/9 绿;pre-push self-check 全过。
- **下次启动**:用户授权完成后,本地 `git push -u origin feature/sdk-phase-0-protocol-foundation` 即可,后续 PR 描述 + auto-merge / plan doc 划线已就绪(commit 已含,分支 ready)。

---

## 2026-05-31 — Phase 0 完成(PR #SDK-P0-1,待 push)

- **范围**:0.1 schemaVersion + reject 未知 major、0.2 5 个 SDK wire DTO records (`sdk/wire/`)、0.3 `SdkWireContractTest`(batch-orchestrator,9 测试)、0.4 dual-rollout 指南 + 新建 `docs/api/orchestrator-internal.openapi.yaml`。
- **实际 vs plan**:工作量 8h(plan 估 8h)持平。比 plan 多做了:`TaskDispatchMessage` 加 7 参兼容构造避免 break 8 个旧测试 + 拉宽 `resolvedMajor` 支持 `vN.M` / `vN-suffix` 两种后缀。比 plan 少做的:**未** refactor `PlatformHttpClient` / scheduler 改用新 records(仍走 `Map<String, Object>`)—— 拆到 Phase 1 顺手做,本 PR 保持 ≤ 600 LoC 净增。
- **环境问题**:本地 JDK 21,但项目要 JDK 25(`.mvn/jvm.config` 用 `--sun-misc-unsafe-memory-access=allow`)→ 临时下载 Oracle JDK 25 + 拷 JDK 21 cacerts 解决 SSL 信任。后续 routine 启动需复用 `/opt/jdk25`。
- **测试结果**:`mvn -pl batch-worker-sdk test` 140/140 绿(原 135 + 新 5);`SdkWireContractTest` 9 测试绿;**未跑** orchestrator 全模块 verify(memory: `feedback_p1a_no_full_test`)。
- **后续**:Phase 1 启动条件已具备(#SDK-P0-1 必须 merge)。Phase 2 协议层在 #SDK-P0-1 + Phase 1 全部 merge 后再开。
