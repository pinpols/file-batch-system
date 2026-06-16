# SDK 接入端到端旅程 — 从 0 到第一个 task 跑通 + 灰度切 buildId

面向**租户运维 / 接入开发者**的一次走完清单:把 SDK 自托管 worker 从「申请凭据」一路串到「灰度切 buildId 后老进程 drain」。每节都引到对应的 quickstart / troubleshooting / runbook / ADR,本文不重复细节。

> 单点查询:
> - 5 分钟跑起一个 worker → [`quickstart.md`](./quickstart.md)
> - 报错对号入座 → [`troubleshooting.md`](./troubleshooting.md)
> - wire 协议字段 → [`wire-protocol.md`](./wire-protocol.md)
> - 平台运维侧(Kafka / 配额 / RLS)→ [`runbook/per-tenant-worker-onboarding.md`](../runbook/per-tenant-worker-onboarding.md)
> - 协议变更纪律 → [`runbook/sdk-dual-rollout.md`](../runbook/sdk-dual-rollout.md)
> - 设计背景 → [ADR-035](../architecture/adr/ADR-035-tenant-self-hosted-worker-sdk.md)

---

## 0. 准备清单(5 分钟)

| 项 | 说明 | 验证 |
|---|---|---|
| JDK 21+ | SDK 使用 Java 21 record / pattern,见 [`quickstart.md` §前置](./quickstart.md) | `java -version` ≥ 21 |
| Maven 3.9+ | 拉 `batch-worker-sdk` 坐标 | `mvn -v` |
| 平台 `BASE_URL` | 形如 `https://batch.example.com`,worker register / heartbeat / claim / report 都走它 | `curl -s "$BASE_URL/actuator/health"` |
| `TENANT_ID` | 平台分配,作为 RLS + topic 后缀的 key | 与 API key 绑定一致(见 §1) |
| `API_KEY` | scope 必须含 `worker.execute`;P2 之后强制,无 fallback | 申请流程见 [per-tenant runbook §api-key](../runbook/per-tenant-worker-onboarding.md) |
| Kafka bootstrap + SASL/SCRAM | prod 必须 SASL_SSL;本地联调可 PLAINTEXT | 见 §1 验证脚本 |
| 一个 `workerCode` | 同租户内唯一,例如 `acme-worker-01` | 注册冲突会 409 |
| 一个 `BUILD_ID` | 自取(commit sha / image tag),灰度切流见 §5 | console fingerprint 看板按此聚合 |

**环境变量两套前缀,二选一不要混**(细节见 [`quickstart.md`](./quickstart.md) §前置 +
[`troubleshooting.md`](./troubleshooting.md) §1):

- `BatchPlatformClientConfig.fromEnv()` 默认 **`BATCH_SDK_`**(`BATCH_SDK_BASE_URL` / `BATCH_SDK_TENANT_ID` / `BATCH_SDK_API_KEY` / `BATCH_SDK_KAFKA_BOOTSTRAP` / `BATCH_SDK_KAFKA_SASL_JAAS_CONFIG` ...)
- `examples/sample-tenant-worker-java/` builder 风格用短前缀 **`BATCH_`**(`BATCH_BASE_URL` / `BATCH_API_KEY` / `BATCH_KAFKA_PROTOCOL` / `BATCH_KAFKA_SASL_JAAS` ...)

> 半混会触发 `missing required env vars: ...`(`troubleshooting.md` §1 第一行)。

---

## 1. Kafka topic + ACL(运维做,一次性)

orchestrator 派单 topic 由 `batch-orchestrator/.../infrastructure/mq/BatchTopicResolver.java` 计算,
**权威命名**(同 [`runbook/per-tenant-worker-onboarding.md` §1](../runbook/per-tenant-worker-onboarding.md)):

```
base:       batch.task.dispatch.{taskType}                # taskType ∈ import|export|process|dispatch|spi
per-tenant: batch.task.dispatch.{taskType}.{tenantId}     # ← 注意是 taskType 在前
例:          batch.task.dispatch.import.bigcorp
```

`tenantId` 中非 `[a-zA-Z0-9._-]` 字符会被 `BatchTopicResolver.safe()` 替换为 `_`,worker 端订阅
正则同步处理。**不要**把顺序写成 `batch.task.dispatch.tenant.{tenantId}.{type}`(早期 plan 草稿
有过这个形态,跟 producer / consumer 都对不上)。

### 1.1 跑脚本建 SCRAM 用户 + ACL

```sh
KAFKA_BOOTSTRAP_SERVER=kafka:29092 \
ADMIN_CONFIG=/tmp/admin-client.properties \
TENANT_ID=bigcorp \
TENANT_PASSWORD='generated-strong-secret-32+chars' \
WORKER_TYPES=import,export \
  sh scripts/data/init-tenant-kafka-acl.sh
```

脚本完成后 stdout 输出 `sasl.jaas.config` 字符串,塞到 K8s secret → worker 容器
`BATCH_KAFKA_SASL_JAAS`(或 `BATCH_SDK_KAFKA_SASL_JAAS_CONFIG`)。

### 1.2 验证

```sh
kafka-topics.sh --bootstrap-server kafka:29092 --command-config /tmp/admin-client.properties \
  --list | grep "^batch\.task\.dispatch\..*\.bigcorp$"

kafka-acls.sh --bootstrap-server kafka:29092 --command-config /tmp/admin-client.properties \
  --list --principal User:bigcorp
```

错排:`SaslAuthenticationException` → [`troubleshooting.md` §1](./troubleshooting.md) 第 3 行。

---

## 2. 写第一个 handler + 上线(开发者做)

走 [`quickstart.md`](./quickstart.md) §「5 步走」:加坐标 → 实现 `SdkTaskHandler` →
`BatchPlatformClient.builder()...register(handler).start()`。本文不重复代码。

启动后**期望看到**(按时间顺序):

1. SDK 侧 `BatchPlatformClient starting (workerCode=acme-worker-01, ...)`,接着 `register OK`、
   `heartbeat OK`、`kafka subscribe pattern=^batch\.task\.dispatch\..*(\.bigcorp)?$`。
2. 平台 console:
   - **worker 在线**:`GET /api/console/workers/fingerprints` 返回该 buildId 1 个实例
     (controller:`batch-console-api/.../ConsoleWorkerFingerprintController.java:28`,
     menu 入口由 Lane M 加 allowlist 后在 UI 出现;无 menu 时直接 curl 可验)。
   - **task type 已注册**:`GET /api/console/custom-task-types`
     (`ConsoleCustomTaskTypeController.java:26`),descriptor JSON schema 来自 handler。

任一未出现 → [`troubleshooting.md` §1 + §2](./troubleshooting.md)(start 失败 / register 401 / 409 conflict)。

---

## 3. 派第一个 task(运维或平台触发)

最简单先用 console-api curl 派一单(完整 task DTO 见 `wire-protocol.md` §「Kafka 派单 payload」):

```sh
curl -X POST "$BASE_URL/internal/tasks/dispatch" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "X-Tenant-Id: bigcorp" \
  -H 'Content-Type: application/json' \
  -d '{"taskType":"echo","parameters":{"msg":"hello"}}'
```

worker 侧期望日志(按时间顺序):

```
kafka poll → claim acquired (taskId=...)
dispatcher.execute taskType=echo handler=EchoHandler
report SUCCESS (taskId=..., elapsedMs=...)
```

**故障路径速查**:

| 现象 | 看 |
|---|---|
| claim 401 / 403 | [`troubleshooting.md` §3](./troubleshooting.md)(API key scope / RLS) |
| dispatch 没到 worker | [`troubleshooting.md` §4](./troubleshooting.md)(订阅 mode、ACL、partition revoke);确认 §1 的 topic 命名 |
| handler 抛异常 → FAILED 但无重试 | [`troubleshooting.md` §5](./troubleshooting.md)(retry policy / DLQ);ADR-035 §「Scheduler 节奏」 |

---

## 4. 看进度 + 取消(运维)

### 4.1 心跳进度

长任务 handler 通过 `SdkTaskContext.progress()` 上报,SDK 把 progress + checkpoint 写到
`batch.job_task.heartbeat_details`(V161)。console 读端点:

```
GET /api/console/tasks/{taskId}/heartbeat-details?tenantId=bigcorp
```

(`batch-console-api/.../ConsoleTaskController.java:23` + `:36`,租户作用域强制在 mapper WHERE,
跨租户 / 不存在均 404。)

### 4.2 取消

console 触发 cancel → orchestrator 经 lease renew 反向带 `cancelled=true` 回 worker。handler
**必须周期自检**:

```java
if (ctx.isCancelled()) {
  return SdkTaskResult.cancelled("user requested");
}
```

API 在 `batch-worker-sdk/.../task/SdkTaskContext.java:125`。无自检的 handler 不会被强杀,
最坏要等 `client.stop(Duration)` 超时(见 §6)。

---

## 5. 灰度切 buildId(运维)

同一 workerCode 起 2 个进程,`BUILD_ID` 不同(例如 `v1.2.0` / `v1.3.0-rc1`):

1. **看分布**:`GET /api/console/workers/fingerprints/summary`
   (`ConsoleWorkerFingerprintController.java:56`),按 buildId 分组的在线实例数。Lane M
   menu 接好后走 UI fingerprint 看板。
2. **切流方式**(任选):
   - **partition revoke**:停 v1.2.0 进程的 Kafka consumer,新单只会落到 v1.3.0 的 partition 上;
     在跑中的 task 走完即结束。无侵入,但粒度粗(整 worker)。
   - **server-side pause**(细粒度):orchestrator 端按 taskType 暂停派发(`HeartbeatDirective`
     带 `pausedTaskTypes`,见 `batch-worker-sdk/.../dispatcher/HeartbeatDirective.java`),
     只 drain 指定 type,其他继续。
3. **drain 老 buildId**:观察 fingerprint summary,v1.2.0 在线 task 归零后 `client.stop(Duration)`
   → 进程退出 → console fingerprint 表自动剔除(§6 的 timeout scheduler 兜底)。

> 协议侧改字段时另走 [`sdk-dual-rollout.md`](../runbook/sdk-dual-rollout.md) 三步纪律(平台先发 → 观察 → SDK 跟进),
> 不要跟 buildId 灰度混在一次窗口。

---

## 6. 故障下线 / 优雅停止

### 6.1 SDK 主动 stop

```java
client.stop(Duration.ofSeconds(60));  // 默认 30s
```

签名见 `batch-worker-sdk/.../client/BatchPlatformClient.java:174`。超时未结束的 task 会打
WARN 列 taskId(由 `TaskDispatcher.stop(Duration)` 报),平台侧 task 留在 RUNNING,靠 §6.2 兜底。

### 6.2 平台兜底

orchestrator `WorkerHeartbeatTimeoutScheduler`(每 30s 扫一次):心跳停更
**默认 timeoutSeconds(90) + graceSeconds(30) = 120s** 后,worker 由 ONLINE/DRAINING
→ OFFLINE(`batch-orchestrator/.../infrastructure/scheduler/WorkerHeartbeatTimeoutScheduler.java:42`)。
OFFLINE worker 的在跑 task 由 lease 过期机制回收(ADR-035 §「Scheduler 节奏」)。

### 6.3 时序校验 fail-fast / WARN 降级(R3-4)

SDK 启动期对 4 条时序规则做 cross-field 校验,违反默认 `IllegalStateException` 让进程挂掉:

| # | 规则 | 默认值 | 违反含义 |
|---|---|---|---|
| 1 | `heartbeatInterval >= 1s` | 30s | 防止极端配置刷爆 orch |
| 2 | `leaseRenewInterval >= 5s` | 60s | 同上 |
| 3 | `leaseRenewInterval <= heartbeatInterval × 3` | 60s ≤ 90s | lease 续约比心跳慢 → in-flight task 被 orch 误判租约过期回收 |
| 4 | `httpTimeout <= heartbeatInterval / 2` | 10s ≤ 15s | 心跳超时排队后 backlog 拖死 scheduler |

**降级开关**(Round-2 P0 #4):配置稍偏时,默认 fail-fast 会触发 K8s 重启循环对运维不友好。可通过
`BATCH_SDK_STRICT_TIMING=false`(或 builder `.strictTimingValidation(false)`)把 4 规则违反从
throw 降级为 `log.warn(...)`,client 仍可 build。**临时口子**,适用于运维降级窗口,不建议长期开启。

```bash
# 紧急降级:接受配置偏差,WARN 不挂进程
export BATCH_SDK_STRICT_TIMING=false

# 默认(生产):严格 fail-fast
unset BATCH_SDK_STRICT_TIMING
# 或显式 export BATCH_SDK_STRICT_TIMING=true
```

只有显式 `false / 0 / no / off`(大小写不敏感)才降级;其它取值(含非法值)仍走 strict,
体现"默认 strict、降级需显式声明"的安全偏好。

### 6.4 K8s 部署建议

- `terminationGracePeriodSeconds` ≥ `client.stop()` 的 timeout + 30s buffer(默认 30s → 至少配 60s;
  长 task 调大)。
- preStop hook:`kill -TERM 1` 触发 SDK shutdown hook(`BatchPlatformClient.java:44` Javadoc 示例)。
- readiness probe 在 SDK `stop()` 期间应转 NotReady,让 service mesh 不再灌入新流量
  (handler 本身仍要靠 `ctx.isCancelled()` 退出)。

---

## 7. 接入完成检查清单

- [ ] §0 所有环境变量配齐,前缀单一不混用
- [ ] §1 Kafka topic 命名符合 `batch.task.dispatch.{taskType}.{tenantId}`,ACL 已授该租户 SCRAM 用户
- [ ] §2 启动后 `/api/console/workers/fingerprints` 看到 worker;`/api/console/custom-task-types` 看到 taskType
- [ ] §3 第一单 echo 跑通,worker 日志有 `report SUCCESS`,平台 task 表状态 SUCCESS
- [ ] §4.1 长任务 `/api/console/tasks/{id}/heartbeat-details` 看到 progress 递增
- [ ] §4.2 cancel 后 handler 在 ≤ heartbeat 周期 (30s) 内退出,task 状态 CANCELLED
- [ ] §5 同 workerCode 双 buildId 在 fingerprint summary 分布符合预期,切流后老 buildId drain 干净
- [ ] §6 SIGTERM → SDK stop → 平台 OFFLINE 时间线 ≤ 2 min,无僵尸 task

---

## 8. 引用

### ADR / 设计
- [ADR-035 tenant self-hosted worker SDK](../architecture/adr/ADR-035-tenant-self-hosted-worker-sdk.md)
  — §3「安全模型」、§「Scheduler 节奏」、§「11 项 triage」

### Runbook
- [`per-tenant-worker-onboarding.md`](../runbook/per-tenant-worker-onboarding.md) — Kafka topic / ACL / 配额 / RLS 一站
- [`sdk-dual-rollout.md`](../runbook/sdk-dual-rollout.md) — 协议字段变更三步纪律
- [`rolling-upgrade-workers.md`](../runbook/rolling-upgrade-workers.md) — 内建 worker 滚升

### SDK 文档
- [`quickstart.md`](./quickstart.md) — 5 步代码示例
- [`troubleshooting.md`](./troubleshooting.md) — 错误对号
- [`wire-protocol.md`](./wire-protocol.md) — Kafka payload / HTTP body

### 关键 PR(本旅程涉及的 SDK + console 端点)
- **#228** `GET /api/console/tasks/{taskId}/heartbeat-details`(§4.1 读端点;`console-api-protocol.md` 2026-06-01)
- **#239 / #240 / #241 / #242** Lane A/D/F/M:SDK stop timeout 分摊、worker fingerprint 端点、fingerprint 看板、console menu allowlist
  (PR # 以合并时为准,可查 `git log --grep "Lane [ADFM]"`)
