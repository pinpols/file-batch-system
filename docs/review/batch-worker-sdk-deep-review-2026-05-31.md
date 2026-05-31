# batch-worker-sdk 深度评估 — 2026-05-31

**范围**:`batch-worker-sdk/` 当前 main 状态(ADR-035 Phase 1 已落地 P1.1–P1.4 + P0 hardening)。
**评估目标**:架构定位 / 协议契约 / 并发模型 / 安全 / 可观测 / API 工效 / 可演进性。
**结论一句话**:协议契约显式化 + `stop()` 顺序是当前最值得马上修的两件事;其他可作 P2 增量。

---

## 评估上下文

- 模块代码量:main 1640 LoC / test 2509 LoC,156 测试,5s 内跑完(无 Spring / 无 Testcontainers)。
- 依赖:`jackson-databind` + `jackson-datatype-jsr310` + `kafka-clients` + `slf4j-api` + `lombok(provided)`,JAR 目标 < 2 MB。
- 协议:HTTP `/internal/{workers,tasks}/*` + Kafka `batch.task.dispatch.<tenant>.*`,API Key + Tenant Id + Idempotency-Key。
- 5 个业务模板:`SdkAbstract{Atomic,Import,Export,Process,Dispatch}Handler`(ADR-036)。

---

## 1. 架构定位 ✅ 总体合理,但有未对齐

**优点**

- 依赖砍到 4 个,无 Spring / 无 batch-common —— ADR-035 P0 项最难做对的事做到了。
- `@Value/@Builder` 配置 + Builder 注册 handler,形态干净。

**真问题**

- 租户进程**直连**平台 Kafka + HTTP,在 P0/P1 阶段可接受,但 ADR-035 自托管要求"平台不暴露内部 topic / 内部表语义"。当前 `batch.task.dispatch.<tenant>.*` 直接订阅,意味着租户 Kafka 凭据可看到整个 dispatch topic 的字节流。`TaskDispatchMessage` **没有 `schemaVersion` 字段**,JSON schema 一旦改字段(加非 optional)立刻 breaking,而且 SDK 编译期不会报。比加任何 feature 都该先做。

---

## 2. 协议契约 ⚠️ 内部 DTO 复制粘贴成事实公开 API(风险最高)

- `PlatformHttpClient` 各方法 body 是 `new HashMap<>().put("workerCode", ...)`,schema "对齐 WorkerHeartbeatDto" 写在注释里。**这是脆契约**:orchestrator 改一个字段名(snake/camel、改必填),SDK 编译期不会报,运行时静默 400。
- **建议**:SDK 端定义自己的 record(`RegisterRequest` / `HeartbeatRequest` / `ClaimRequest` / `ReportRequest` / `RenewRequest`),不引平台 DTO,但作为 SDK 显式 wire schema。平台侧加契约测试(orchestrator 启动或 CI 阶段验证 SDK record 反序列化到平台 DTO 不丢字段)。把"DTO 字段一致性"从注释升级成 CI 拦截。
- `register()` 用 `capabilityTags` 表达 `taskTypes` 是 hack(注释自承),平台早晚分两个字段;SDK 没准备好接 `taskTypes` 独立化。

---

## 2.5 重派与幂等(2026-05-31 补)

平台对同一 `taskId` 在 **6 种场景下** 会重新派单(详见 `docs/design/worker-deployment-models.md` §12):
Lease 超时 / Worker 失联 / 业务失败重试 / 工作流补偿 / 运维手动 / Trigger 周期。

SDK 框架做对的:`Idempotency-Key` 注入 + CLAIM 失败不执行 handler + Lease renewal scheduler 主动续约。

SDK 框架**没做、值得补**的:

- ❌ **`inFlight` set 跨进程不互通**:Worker A 卡死(GC / 长 Full GC)→ lease 超时 → orchestrator 派给 Worker B → A 恢复后继续跑 → **同 task 双进程并行**。SDK 当前没机制让 A 主动 abort handler(没传 cancellation token)。
- ❌ **没传 `attempt_no` 给 handler** —— 租户无法做"首次发邮件,重试跳过"语义。
- ❌ **没引导业务幂等** —— 缺 README 章节告诉租户用 `taskId + partitionInvocationId` 当业务键。

详细修法见 §优先级建议 P0/P1 三项新条目。

---

## 2.6 调度上下文缺失(2026-05-31 补)

**当前 `TaskDispatchMessage` 只有 `parameters / runtimeAttributes`,没有任何"业务日 + 调度元信息"上下文**。SDK 是"瞎子",这导致两类真问题:

### 2.6.1 日切(batch_day)感知缺失

| 端 | 知道 batch_day 吗? |
|---|---|
| 平台 worker | ✅ `BatchDateService` 直读 `batch_day` 表 |
| 自托管 SDK | ❌ 协议层完全没传 |

租户业务实际需要 bizDate 做:按业务日分区 / 跨日逻辑 / 节假日判断 / 跟平台对账日期对齐。
租户自己算(`LocalDate.now()`)会出 bug:跨午夜执行错位、节假日规则不同步、跨时区错位。

**正确做法**:平台调度时刻拍好 bizDate,通过 `TaskDispatchMessage.schedulingContext` 推下去。**只下沉值,不下沉规则**(节假日表 / 日切逻辑留在平台)。

### 2.6.2 调度暂停 / 平台异常感知缺失

平台运维做的事 SDK 一概不知:

| 平台运维动作 | SDK 当前感知? | 后果 |
|---|---|---|
| `trigger_definition.status = PAUSED` | ❌ | 已派出的还在跑 |
| Orchestrator 服务降级 / 挂了 | ❌ | SDK 继续消费,REPORT 失败堆积 |
| 平台全局限流 | ❌ | SDK 还按配置满载 |
| 租户被冻结 | ❌ | 仍在接活 |

**正确做法**:`heartbeat` response 携带 platform directive(双向通道):

```json
{
  "platformStatus": "DEGRADED",
  "desiredMaxConcurrent": 2,
  "shouldDrain": false,
  "pausedTaskTypes": ["tenant_xyz_old"],
  "bizDate": "2026-06-01"
}
```

SDK 端按 platformStatus 状态机响应:NORMAL / DEGRADED(降并发)/ PAUSED(`KafkaConsumer.pause`) / DRAINING(不接新等 in-flight)。

### 2.6.3 协议建议

`TaskDispatchMessage.schedulingContext` 段:

```json
{
  "bizDate": "2026-05-31",
  "prevBizDate": "2026-05-30",
  "nextBizDate": "2026-06-01",
  "isHoliday": false,
  "triggerCode": "tenant_xyz_daily",
  "triggerType": "CRON",
  "workflowRunId": 98765,
  "attemptNo": 1
}
```

`SdkTaskContext` 新增 getter:`bizDate() / prevBizDate() / nextBizDate() / isHoliday() / attemptNo() / triggerCode() / workflowRunId()`。

详细设计见 `docs/design/worker-deployment-models.md` §5.1。

---

## 2.7 自定义 taskType 注册缺失(2026-05-31 补)

**当前 SDK 跟平台之间没有"taskType 描述符"协议**,导致租户运营 / PM 在 console 上**完全不知道某个自定义 taskType 需要传什么参数**。

### 2.7.1 缺失项

- ❌ `custom_task_type_registry` 表(不存在)
- ❌ console "我的 taskType" 注册 / 列表页
- ❌ SDK `register()` 上报 schema / defaults / retry / timeout
- ❌ workflow 编辑器按 schema 渲染表单 / 校验
- ❌ taskType 级默认参数 + 默认重试策略
- ❌ `task.effective_parameters` 审计快照

### 2.7.2 当前后果

- 运营拖一个 `tenant_xyz_import` 节点 → parameters 是空 JSON 框 → 只能瞎填
- 填错时 task 派到 SDK 抛 `ClassCastException` → 配置错位运行时才暴露
- handler 升级了字段名,运营不知道 / 没改 workflow_node.parameters → 派单失败堆积

### 2.7.3 建议设计

详见 [`docs/design/sdk-task-type-configuration.md`](../design/sdk-task-type-configuration.md)。核心:

1. 新表 `custom_task_type_registry`(tenant_id + task_type_code 主键)
2. SDK `SdkTaskHandler.descriptor()` 暴露 schema / defaults / retry / timeout
3. `register()` 上报 `taskTypes[].descriptor`,平台 upsert
4. orchestrator 派单时合并 `defaults + node.parameters + 模板替换` → `TaskDispatchMessage.parameters`
5. console 编辑器按 schema 渲染表单 + 必填 / 类型 / 范围校验

### 2.7.4 敏感凭据约束(硬规约)

DB 密码 / OAuth secret / 加密密钥**禁止**走 console parameters(明文存 DB + 明文进 Kafka)。一律 K8s Secret + env var,SDK handler 构造时读。需在 SDK README 写死。

---

## 3. 并发与状态机 ⚠️ 几个边角 bug 风险

### 3.1 Kafka commit 时序错位(目前 OK,但易被破坏)

- `KafkaTaskConsumer.run()`:poll → `dispatcher.onMessage()`(submit 线程池,立即返回)→ `commitSync()`。
- 当前 `Executors.newFixedThreadPool` 用 unbounded queue,所以 submit 不会 reject。
- **风险**:有人改成 bounded queue,`RejectedExecutionException` 静默丢消息 + offset 已 commit。
- **修法**:在 `TaskDispatcher` 构造处显式锁死/注释 unbounded 约定;或显式 try-catch RejectedExecutionException 退回 commit。

### 3.2 背压与 rebalance 相互作用(真 bug)

- `applyBackpressure()` `consumer.pause(consumer.assignment())` 不停 poll,这是对的。
- 但 `consumer.subscribe(Pattern)` 是动态 assignment,**rebalance 之后新 partition 不会继承 paused 状态** —— Kafka client `pause()` 只对当前 assignment 有效。
- **后果**:rebalance 期间 in-flight 还满,新 partition 立刻消费,超过 `maxConcurrentTasks`。
- **修法**:实现 `ConsumerRebalanceListener.onPartitionsAssigned()`,assignment 变化时 re-evaluate paused 状态。

### 3.3 CLAIM 失败一律静默(真 bug)

- `processCore` 里 CLAIM 失败 `return`,注释认为"通常是被 peer 抢了"。
- **但** 401(API Key 失效)/ 5xx 走同一路径 → 租户进程陷入"poll → claim 401 → 静默 → 下一条"安静死循环,heartbeat 还在跑(平台以为活着)。
- **修法**:401/403 fail-fast + stop+exit;5xx 指数退避并暴露指标。

### 3.4 Lease renewal vs in-flight set 竞态

- `dispatcher.inFlightTaskIds()` 是瞬时 snapshot;renew 过程中任务可能已完成 + REPORT 已发出,renew 是 no-op 或 404 —— 已被 catch 吞,只 warn,OK。
- **但**:REPORT 成功后到 `inFlight.remove()` 之间 renew 调入,会续约一个已完成任务,可能干扰 orchestrator 状态判定。需要 orchestrator 端确认对 terminal task renew 的返回行为是否幂等。

### 3.5 Heartbeat scheduleAtFixedRate

- `tick()` 内 `catch (Throwable)` 已吞,scheduler 不会因抛而停。
- **但**:HTTP 阻塞 10s + 心跳间隔 30s,连续 3 次超时会堆积追赶式调用。
- **修法**:`scheduleWithFixedDelay` 比 `scheduleAtFixedRate` 更适合本场景。

---

## 4. 安全 ⚠️ 该做的做了,但有日志泄漏面

- ✅ `X-Batch-Api-Key` / `X-Batch-Tenant-Id` 注入对;✅ Idempotency-Key UUID 对;✅ API Key 不进 header log。
- ⚠️ **错误体 truncate 500 字节进 IOException message**:`throw new IOException("HTTP " + ... + " body=" + truncate(errBody, 500))`。如 orchestrator 错误响应 echo 敏感字段,会在租户进程日志里被打。**修法**:错误 body 走 `log.debug` 或哈希后吐,IOException message 只放 status + url。
- ⚠️ `kafkaSaslJaasConfig` 是明文 String,持 SCRAM 密码。注释提醒"不要硬编码",但 SDK 没主动给"从文件/Secret 读" 的 helper。建议 `BatchPlatformClientConfig.loadKafkaSaslFromFile(Path)` 一行 helper,降低租户用错概率。
- ⚠️ TLS:`HttpClient.newBuilder().connectTimeout(...).build()` 没显式 TLS 版本/证书 pinning。HTTPS baseUrl 走 JVM 默认 truststore —— 租户运维如未装企业 CA 就握手失败。**修法**:README 加一节"TLS 凭据如何注入"。

---

## 5. 可观测性 ❌ 这块最薄

- MDC 的 `traceId/tenantId/taskId` 透传是好的。
- **但**整个 SDK 没有任何指标暴露面(不该引 Micrometer)。至少应:
  - 暴露 `BatchPlatformClient.metrics()` 返回简单 POJO:`tasksProcessed / tasksFailed / claimFailures / reportFailures / inFlightCount / lastHeartbeatAt`。
  - 租户接自己的 Prometheus 需要可读快照;现在只能 grep 日志。
- **没健康检查**:租户进程想知道"SDK 是否健康"只能间接看心跳成功率。给一个 `client.isHealthy()` boolean 即可,基于"最近 N 次 heartbeat 成功 + kafka consumer thread alive"。
- **KafkaTaskConsumer 异常退出**(`Throwable t` catch)后整个 consumer 线程死了,**进程不退也不通知**,租户业务无感知。要么 setUncaughtExceptionHandler 让进程退,要么把"consumer thread 死"上报到 `isHealthy()`。

---

## 6. API 工效 ⚠️ Abstract handler 偏理论

- 5 个 `SdkAbstract*Handler` 模板方法对应 ADR-036,设计意图好。**但**:
  - `SdkAbstractImportHandler` 把 `openSource / readRows / loadBatch / batchSize` 当抽象,**没有错误行容忍策略** —— 一行解析失败整批 fail。Import 真实场景 95% 需要"跳过坏行 + 累计"。`SdkRowResult` 已有 success/fail 字段但模板没暴露失败计入路径。
  - 没有显式 streaming / AutoCloseable 语义。`openSource` 不返 handle,`cleanup()` 由子类记 state 关 —— 容易泄漏 SFTP / 文件流。**修法**:`protected abstract AutoCloseable openSource()`,模板内 try-with-resources。
- `SdkTaskResult.fail(Throwable)` vs `fail(String, Throwable)` vs `fail(String)` 在 `SdkAbstractTaskHandler` 里被反复重组(`t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()`),**cause chain 没保留**,排障会丢上下文。

---

## 7. 可演进性 ⚠️ 协议升级路径未铺

- **TaskDispatchMessage 没 schemaVersion**。今天 OK,明天加字段全租户升级。
- **没有 server-driven config**:`maxConcurrentTasks` / `heartbeatInterval` 是租户进程写死;平台想全局限速(orchestrator 过载要求 SDK 降并发)做不到。**建议**:`heartbeat` response 允许返 `serverConfig` 段,SDK 动态调整。Zeebe / Temporal 都做的事,P2+ 该补。
- **`stop()` 顺序问题**(真 bug):当前是 `heartbeat stop → leaseRenewal stop → kafka stop → join → dispatcher.stop`。**问题**:heartbeat 先停意味着 dispatcher 还在跑 in-flight 任务的 30s drain 窗口里,平台不再收心跳,可能在这 30s 内判死并 reassign 已 in-flight 任务,**重复执行**。
- **正确顺序**:**先 stop Kafka(不接新)→ drain dispatcher → 最后 stop heartbeat & lease renewal → deactivate**。心跳 + lease renewal 必须活到 dispatcher 真排空。

---

## 优先级建议(按 ROI)

| 优先级 | 项 | 工作量 | 文件 |
|---|---|---|---|
| 🔴 P0 | `TaskDispatchMessage` 加 `schemaVersion` + reject 未知 major | 0.5d | `dispatcher/TaskDispatchMessage.java` |
| 🔴 P0 | `stop()` 顺序倒过来(心跳 / lease 最后停) | 0.2d | `client/BatchPlatformClient.java` |
| 🔴 P0 | CLAIM 401/403 fail-fast,不要静默 | 0.3d | `dispatcher/TaskDispatcher.java` |
| 🔴 P0 | Lease revoked 时通知 handler(`SdkTaskContext.isCancelled()` + LeaseRenewal 检测 404/410/410-Gone 标记 revoked)— 防双进程并发执行同 task | 0.5d | `task/SdkTaskContext.java` + `scheduler/LeaseRenewalScheduler.java` |
| 🟡 P1 | `SdkTaskContext.attempt()` 暴露 `attempt_no` —— handler 区分首次 vs 重试 | 0.2d | `task/SdkTaskContext.java` + `dispatcher/TaskDispatchMessage.java` |
| 🟡 P1 | README 加「业务幂等」章节 + 推荐键示例(`taskId + partitionInvocationId`) | 0.2d | `README.md` |
| 🟡 P1 | `ConsumerRebalanceListener` 处理 paused 状态恢复 | 0.5d | `dispatcher/KafkaTaskConsumer.java` |
| 🟡 P1 | `TaskDispatchMessage.schedulingContext` 段(bizDate / prev / next / isHoliday / attemptNo / triggerCode / workflowRunId)| 1d | orchestrator 派单 + SDK DTO |
| 🟡 P1 | `SdkTaskContext.bizDate()` / `prevBizDate()` / `isHoliday()` / `attemptNo()` API | 0.3d | `task/SdkTaskContext.java` |
| 🟡 P1 | Heartbeat response 携带 `platformStatus` + `desiredMaxConcurrent` + `pausedTaskTypes` | 1.5d | orchestrator heartbeat handler + SDK `HeartbeatScheduler` |
| 🟡 P1 | SDK 端 platformStatus 状态机(NORMAL/DEGRADED/PAUSED/DRAINING)+ dispatcher 联动降速 | 0.5d | SDK |
| 🟢 P2 | Kafka consumer lag 自检 + 暴露到 `metrics()` | 0.3d | `dispatcher/KafkaTaskConsumer.java` |
| 🟢 P2 | CLAIM / REPORT 连续失败 fail-fast 阈值 | 0.3d | SDK |
| 🔴 P0 | `custom_task_type_registry` 表 + Flyway + archive 镜像 | 0.5d | orchestrator schema |
| 🔴 P0 | `SdkTaskHandler.descriptor()` + `SdkTaskTypeDescriptor` 类 + register 上报 | 1d | SDK + orchestrator register handler |
| 🔴 P0 | orchestrator 派单合并 `defaults + node.parameters + 模板替换` | 1d | orchestrator `TaskDispatchService` |
| 🟡 P1 | `task.effective_parameters` 审计快照字段 | 0.5d | orchestrator schema + 派单逻辑 |
| 🟡 P1 | console "我的 taskType" 列表 / 详情页 | 1d | console-api + FE |
| 🟡 P1 | console 工作流编辑器按 schema 渲染表单 + 模板补全 | 2-3d | FE 工作流编辑器 |
| 🟡 P1 | SDK README 加"敏感凭据走 env,不走 parameters"硬规约 | 0.2d | `README.md` |
| 🟡 P1 | SDK 自有 wire DTO(records)+ 平台契约测试 | 1d | 新文件 `internal/wire/*` + orchestrator 侧 contract test |
| 🟡 P1 | `BatchPlatformClient.metrics()` + `isHealthy()` | 0.5d | `client/BatchPlatformClient.java` |
| 🟡 P1 | Heartbeat 改 `scheduleWithFixedDelay` | 0.1d | `scheduler/HeartbeatScheduler.java` |
| 🟡 P1 | IOException message 不含 errBody 明文 | 0.1d | `internal/PlatformHttpClient.java` |
| 🟢 P2 | Heartbeat response 携带 serverConfig 动态降级 | 1d | 跨端协议变更 |
| 🟢 P2 | `openSource` 返 AutoCloseable + Import 行容错 | 0.5d | `handler/SdkAbstractImportHandler.java` |
| 🟢 P2 | `loadKafkaSaslFromFile(Path)` helper + TLS README | 0.3d | `client/BatchPlatformClientConfig.java` + README |

---

## 相关 ADR

- **ADR-035** SDK 自托管 — 本评估主要对标范围
- **ADR-036** SDK 五大业务模板 — §6 工效问题
- **ADR-029** Atomic worker 隔离 — `SdkAbstractAtomicHandler` 模板对应平台 atomic worker 的租户侧入口

## 维护说明

P0/P1 修复建议各自单 PR,小步快跑;每个改动都应该:
1. 不破坏现有 156 测试(P0 hardening 已建立的不变式)。
2. 协议层改动同步更新 `docs/api/`(若涉及 `/internal/*`)。
3. `schemaVersion` / `serverConfig` 等协议演进 — 双向兼容窗口至少跨一个发布周期。
