# SDK Roadmap H2-2026 — 进度记录

> 日期倒序。每个 phase 收官后追加 5 行 retro:实际 vs plan diff + 经验。
> 维护规则参见主 plan §1 决策 #隐-1。

---

## 2026-05-31 — Phase 1 #SDK-P1-2 CLAIM 401/403 fail-fast + 5xx 指数退避

- **范围**:1.2 `TaskDispatcher.claimWithRetry()` 按 HTTP 状态码分类:401/403 → `fatal` flag 置 true + `onMessage` 立刻 drop 后续消息(log ERROR);409 → INFO 给 peer;其它 4xx → WARN 放弃;5xx + 传输 `IOException` → `claimMax5xxRetries` 次指数退避(基准 `claimRetryBaseDelay`,200/400/800ms),耗尽放弃。新 `PlatformHttpException extends IOException` 暴露 `statusCode` + `isAuthError/isConflict/isServerError/isClientError`。`PlatformHttpClient` 非 2xx 改抛新异常(`IOException` 兼容,旧调用方 `catch (IOException)` 仍 OK)。
- **实际 vs plan**:工作量 ~2h(plan 估 2h)持平。比 plan 多做:`BatchPlatformClientConfig` 加 `claimMax5xxRetries`(default 3)+ `claimRetryBaseDelay`(default 200ms),`@Builder(toBuilder = true)` 让测试能 derive 配置;暴露 `TaskDispatcher.isFatal()` 给后续 `BatchPlatformClient.isHealthy()`(#SDK-P1-4)消费。比 plan 少做:**没**真把 fatal 串到 `isHealthy()` 或 `System.exit` —— 只保证后续 dispatch 拒收 + log ERROR,让 K8s liveness / 运维介入(memory: `feedback_yagni`),留 P1-4 / P7-3 收。
- **环境坑**:JDK 25 在 `/opt` 不存在,重做 P0/P1-1 那套:`curl /tmp/jdk25.tgz` → `tar` 到 `/opt/jdk25-extract/jdk-25.0.3` → symlink `/opt/jdk25`,cacerts 从系统 JDK 21 拷过去。容器重建会丢,下次 fire 重复一遍即可。
- **测试结果**:`mvn -pl batch-worker-sdk test` 161/161 绿(原 146 + 新 10 `TaskDispatcherClaimRetryTest` + 5 `PlatformHttpExceptionTest`);旧 `TaskDispatcherTest.claimFailureDoesNotExecuteOrReport` 把裸 `IOException("409")` 换成 `PlatformHttpException(409, ...)` 才准。
- **后续**:#SDK-P1-3 接力(`HeartbeatScheduler` 改 `scheduleWithFixedDelay` + `IOException` message 去 errBody 明文);依赖图(§15.8.A)纯 SDK 链是严格串行,本 PR merged 才能开 P1-3。

---

## 2026-05-31 — Phase 1 #SDK-P1-1 stop() 顺序 + ConsumerRebalanceListener

- **范围**:1.1 `BatchPlatformClient.stop()` 顺序倒置(Kafka 先 wakeup+join → dispatcher drain → heartbeat/lease close → deactivate)、1.3 `KafkaTaskConsumer` 新增 `PauseAwareRebalanceListener` 内部类,`onPartitionsAssigned()` 时若 backpressure 仍激活则 re-pause 新分到的 partition;附 6 测试(3 stop order + 3 rebalance)。
- **实际 vs plan**:工作量 ~3h(plan 估 3h+4h=7h)显著低于预算。比 plan 多做:把 `KafkaConsumer<String, byte[]>` 字段类型 widen 到 `Consumer<String, byte[]>` 让 MockConsumer 能注入;`paused` 字段加 `volatile`。比 plan 少做:Listener 没暴露 metric(`rebalance_count` 等),留给 P1-4 `metrics()` PR 顺手加。
- **环境坑**:JDK 25 在 `/opt` 不存在,curl 从 `download.oracle.com/java/25/latest/jdk-25_linux-x64_bin.tar.gz` 下载到 `/tmp/jdk25`,cacerts 从系统 JDK 21 拷过去解决 SSL 信任。后续 routine 启动复用 `/tmp/jdk25` 即可。
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
- **环境坑**:本地 JDK 21,但项目要 JDK 25(`.mvn/jvm.config` 用 `--sun-misc-unsafe-memory-access=allow`)→ 临时下载 Oracle JDK 25 + 拷 JDK 21 cacerts 解决 SSL 信任。后续 routine 启动需复用 `/opt/jdk25`。
- **测试结果**:`mvn -pl batch-worker-sdk test` 140/140 绿(原 135 + 新 5);`SdkWireContractTest` 9 测试绿;**未跑** orchestrator 全模块 verify(memory: `feedback_p1a_no_full_test`)。
- **后续**:Phase 1 启动条件已具备(#SDK-P0-1 必须 merge)。Phase 2 协议层在 #SDK-P0-1 + Phase 1 全部 merge 后再开。
