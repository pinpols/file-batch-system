# batch-worker-sdk 业界对标差距分析

**状态**:路线图参考 · 2026-05-31
**对标系统**:Temporal · Zeebe (Camunda 8) · Conductor (Netflix) · AWS Step Functions Activity Worker
**关联文档**:[worker-deployment-models](./worker-deployment-models.md) · [深度评估](../review/batch-worker-sdk-deep-review-2026-05-31.md) · [ADR-035](../adr/ADR-035-tenant-self-hosted-sdk.md)

> 本文跟 review doc 不同:review 是当前实现的"近期修复清单",本文是**跨季度路线图参考** —— 跟 Temporal / Zeebe 等成熟系统对标,识别长期演进方向。

---

## 1. 一句话定位

> 我们的 SDK **架构形态**接近 Conductor,**协议契约严格度**接近 Temporal 但弱很多,**租户隔离**强过两者,**长任务可控性**远不如 Temporal。
>
> 真正企业级落地之前,最该补的是 **heartbeat-with-details + cancel push + testkit** 这三件 —— 没这三件,租户跑大批量任务会反复遇到问题,且没法在本地复现。

---

## 2. 横向对标矩阵(13 维)

| 维度 | Temporal | Zeebe (Camunda 8) | Conductor (Netflix) | AWS SFn Activity | **本 SDK** |
|---|---|---|---|---|---|
| **传输** | gRPC long-poll + streaming | gRPC streaming (8.3+) | HTTP long-poll | HTTP long-poll | **Kafka push + HTTP report** |
| **协议版本** | proto + workflow versioning + build IDs | API version negotiation | Task def version | API version | ❌ **无** |
| **任务 schema** | Protobuf / 强类型 / 自定义 codec | JSON + 类型转换 | JSON | JSON | `Map<String,Object>` 无 schema |
| **进度上报 / heartbeat 携带 details** | ✅ 任意 bytes payload | ⚠️ 仅 timeout 续约 | ✅ `IN_PROGRESS` + output | ✅ `SendTaskHeartbeat` with details | ❌ **无 details** |
| **取消信号 push** | ✅ 通过 heartbeat 返回 cancel | ✅ Job 取消 | ⚠️ 轮询发现 | ✅ via heartbeat response | ❌ **无,要 60s 轮询** |
| **超时类型** | 4 种(schedule/start/run/heartbeat) | 2 种(job timeout / process timeout) | 2 种 | 2 种 | **1 种 lease TTL** |
| **背压 / 限流** | task queue 分区 + server rate limit | maxJobsActive + server | poll batch size | poll 间隔 | ✅ pause/resume |
| **多租隔离** | Namespace(v1,数据未严格隔离) | Tenant ID(8.3+) | Domain | AWS account | ✅ **租户 Kafka topic + SASL** |
| **依赖体积** | Java SDK ~20 MB | Java client ~15 MB | Java client ~8 MB | AWS SDK ~5 MB | ✅ **~2 MB** |
| **观测性** | Web UI + 完整 history replay + OTel | Operate UI + Zeebe Exporter | UI + 历史 | CloudWatch | ❌ **无 worker 端指标** |
| **测试基建** | TestServer / TimeSkipping / Replay test | EmbeddedBroker / ZeebeProcessTest | 内嵌 server | LocalStack | ⚠️ 仅 JDK HttpServer stub |
| **加密 / Codec** | DataConverter(自定义加密) | Payload encryption(企业版) | (无内置) | KMS 集成 | ❌ **无** |
| **Worker 身份 / fingerprint** | host + pid + build + sdk version | host + workerName | host + ip | ARN | ⚠️ 仅 `workerCode` |

---

## 3. 我们超出业界基线的部分

### ✅ 多租隔离做得比 Temporal 还彻底

- Temporal Namespace v1 只是逻辑隔离,**数据库共享**;Zeebe 8.3 刚加 tenant-id 是补丁
- 我们一开始就是 `tenant_id` 全表 + Kafka topic 物理分租户 + SASL/SCRAM per-tenant ACL —— **更严格**

### ✅ 依赖最小化(SDK 2 MB)

- Temporal Java SDK: ~20 MB,带 gRPC + protobuf + opentelemetry
- Zeebe Java client: ~15 MB
- 我们 4 个依赖,无 Spring —— **租户接入门槛低 90%**

### ✅ Atomic worker dual-use RCE 隔离(ADR-029)

- Temporal / Zeebe / Conductor **都没专门处理** "任意 shell / SQL 执行" 的隔离边界
- 我们独立 worker 模块 + 自托管让租户拿回任意命令执行权限 —— **企业合规友好**

### ✅ Kafka 派单(对 batch 场景)

- 业界主流是 long-poll(gRPC / HTTP),适合"任意时刻立刻派"
- 我们 Kafka 适合"批量任务、能重放、能审计字节流" —— **batch 场景天然适配**
- Cost:有 rebalance 复杂度(评估文档已点)

---

## 4. 跟业界基线对比真缺的能力

### 4.1 🔴 P0:Heartbeat 携带任意 details(Temporal 最关键的设计)

Temporal heartbeat 不只是"我还活着",而是携带 **任意 bytes payload**:

```java
// Temporal pattern
Activity.getExecutionContext().heartbeat(checkpoint);

// Activity retry 时
Optional<MyCheckpoint> last = Activity.getExecutionContext().getHeartbeatDetails(...);
// 拿到上次的 checkpoint,继续跑
```

**作用**:
- 长任务**断点续跑**(import 5000 万行,3000 万崩 → retry 从 3000 万续)
- 进度可视(平台 UI 显示最新 checkpoint)
- 取消信号反向(heartbeat response 含 `cancelRequested`)

**我们现在**:heartbeat 只传 workerCode/status/currentLoad,**没任何业务 payload**。retry = 从零开始,这是大批量任务的硬伤。

**建议**:把前面讨论的 `/tasks/{t}/progress` + `/tasks/{t}/checkpoint` 合并成 **"task heartbeat with details"**,跟 lease renewal 同 endpoint。一次调用完成 lease 续约 + 进度 + checkpoint + 取消感知。

### 4.2 🔴 P0:多种 timeout 语义(只有 lease TTL 不够)

Temporal 4 种 timeout 解决不同问题:

| 类型 | 含义 | 我们对应 |
|---|---|---|
| `scheduleToStart` | 排队多久没 worker 接 | ❌ 没有,平台只看 lease |
| `startToClose` | 任务最长跑多久 | ⚠️ 仅 lease TTL,意义不同 |
| `scheduleToClose` | 总耗时(含 retry) | ❌ 没有 |
| `heartbeat` | 多久没心跳算挂 | ✅ lease TTL ≈ 这个 |

**真实问题**:租户任务长期停滞跑了 6 小时(每分钟正常 heartbeat),平台**看不出来这是异常**。Temporal 的 `startToClose` 直接 timeout 杀掉。

**建议**:`workflow_node` 上加 `taskTimeout` 字段,平台超时强制 cancel(发 cancel signal 给 SDK)。

### 4.3 🔴 P0:取消信号 push,而不是 60s 轮询

Temporal 在 heartbeat response 里携带 `cancelRequested=true`,worker 立刻知道。
我们现在:平台 cancel 后,SDK 只在下次 lease renew(60s)才发现。

**最差情况**:平台撤销 task → 60s 后 SDK 发现 → 期间业务副作用(发邮件 / 转账)已经发生。

**建议**:对应 `SdkTaskContext.isCancelled()` + heartbeat / renew response 都携带 cancel flag。

### 4.4 🟡 P1:强类型 task 输入输出(`Map<String,Object>` 是反模式)

业界都有类型安全机制:

```java
// Temporal
@ActivityInterface
public interface ImportActivity {
    ImportResult run(ImportRequest req);  // ← 强类型
}

// Zeebe
@JobWorker(type = "tenant_xyz_import")
public ImportResult handle(@Variables ImportRequest req) { ... }

// Conductor
@WorkerTask("tenant_xyz_import")
public ImportResult handle(ImportRequest req, TaskContext ctx) { ... }

// 我们
SdkTaskResult execute(SdkTaskContext ctx) {
    Object x = ctx.parameters().get("filePath");  // ← String? Long? 谁知道
    String fp = (String) x;                       // ← 强转,运行时失败
}
```

**建议**:
- 加 `SdkTypedTaskHandler<I, O>` 泛型基类,框架内 Jackson 反序列化 `parameters` → I,handler 返回 O 序列化进 `outputs`
- 配套 JSON Schema 校验 input(可选)

### 4.5 🟡 P1:测试基建("假平台" 缺失)

Temporal 给租户的:`TestWorkflowEnvironment` 内嵌完整 server,租户写测试 = 单元 + 集成一把抓。
Zeebe:`ZeebeProcessTest` 同上。
我们:JDK HttpServer stub —— **只能测 SDK 自己**,租户业务集成 SDK 后没法测"端到端 dispatch → execute → report"。

**结果**:租户接入 SDK 第一周必踩"上线后才发现协议错位"的问题。

**建议**:发布 `batch-worker-sdk-testkit` 子模块,提供:
- `FakeBatchPlatform`(实现完整 `/internal/*` + 内嵌 KafkaServer / EmbeddedKafka)
- `TaskDispatchMessage.Builder` 测试夹具
- `@BatchWorkerTest` JUnit5 扩展

这是租户接入门槛降一个数量级的关键。

### 4.6 🟡 P1:Payload codec(数据加密)

Temporal `DataConverter`:租户可注入加密 codec,**task input/output 在 transit + at rest 都加密**,平台运维看不到明文。

**真实场景**:租户身份证号 / 银行卡号 经 Kafka 派单消息,平台 Kafka admin 用 `kafka-console-consumer` 一抓就看到明文 —— 合规事故。

**建议**:`BatchPlatformClientConfig` 加 `payloadCodec` 选项,SDK 自动 encrypt(write)/ decrypt(read);平台只见密文。

### 4.7 🟡 P1:Worker fingerprint / build ID

Temporal worker 注册时上报:`host / pid / sdkVersion / buildId / capabilities`。`buildId` 是关键 —— 支持**金丝雀部署**:平台路由 10% 任务给 `buildId=v1.5.0-canary`,90% 给 stable。

**我们现在**:只有 `workerCode`,没法做版本切流。租户升级 SDK 必须全量切。

**建议**:`register` 多塞 `buildId / sdkVersion / hostName / pid`,平台 worker 表存这些;`workflow_node` 可选 `targetBuildId` 字段做切流。

### 4.8 🟢 P2:OpenTelemetry context propagation

业界都做了:dispatch 消息带 `traceparent` header,handler 自动 continue span。
我们:MDC 透 traceId 字符串,**不是 OTel context** —— 跟租户的 OTel collector 接不上,链路在 SDK 这里断了。

**建议**:`TaskDispatchMessage.runtimeAttributes.traceparent` + SDK 集成 OTel propagator(可选依赖,避免强引)。

### 4.9 🟢 P2:Task input 校验(JSON Schema)

Conductor / Zeebe 支持给 task type 注册 input schema,平台派单前校验。

**真实场景**:platform 编排时填错 `parameters`,task 派到 SDK 才发现 → handler 失败 → retry 3 次 → 才报上来。
JSON Schema 在 platform 侧拦截,**问题在编排时就暴露**。

### 4.10 🟢 P2:Sticky / 亲和性的实际语义

Temporal "sticky task queue" 保证 workflow 同一 worker 处理(性能优化 + 缓存复用)。
我们 ADR-027 提了亲和性,**没在 SDK 接口体现**(worker 不知道自己跑过哪些 task)。

### 4.11 🟢 P2:Worker shutdown 远程信号

Temporal `Worker.shutdown()` 可被平台远程触发(运维窗口"请下线"),worker 收到信号优雅退出,**不需要租户人工 kill**。
我们现在:运维要联系租户走自家 K8s 下线流程。

**建议**:控制 topic `batch.task.control.<tenantId>` push `WORKER_SHUTDOWN_REQUESTED`(评估文档已规划)。

### 4.12 🟢 P2:Resource quota / rate limit(平台侧)

Temporal namespace 有 per-task-queue rate limit:防租户 abuse。
我们 currently 没有 —— 一个租户跑疯了,Kafka topic 堆 100万条消息,平台 orchestrator 派单线程被打满。

**建议**:`workflow_definition` 加 `taskRateLimit`,orchestrator 派单时 throttle。

---

## 5. 刻意不做的(N/A 项)

| 业界有的 | 我们为什么不做 |
|---|---|
| **Workflow code as data(Temporal)** | 我们是 Conductor 形态:workflow 是数据(JSON / console 配),不是代码。这是设计选择,不是缺失 |
| **Deterministic replay** | 同上,N/A |
| **Signal API** | 我们走 "暂停 + 审批节点",不通过 signal |
| **Sub-workflow** | 平台 workflow 支持 sub-workflow,SDK 不该掺和 |
| **Continue-as-new** | 长 workflow 重启技巧,我们用 trigger 重新 fire 替代 |

---

## 6. 综合优先级排名(跨季度路线图)

把 review doc 已有的 P0/P1 跟本文新发现的对标缺口合并:

| 优先级 | 项 | 类别 | 工作量 | 业界对标 |
|---|---|---|---|---|
| 🔴 P0 | Heartbeat 携带任意 details(合并 progress / checkpoint / lease)| 长任务可控性 | 1.5d | Temporal heartbeat details |
| 🔴 P0 | Cancel push(heartbeat / renew response 携带 cancelRequested) | 副作用控制 | 0.5d | Temporal cancel |
| 🔴 P0 | 多种 timeout(`taskTimeout` startToClose 语义) | 异常回退 | 1d | Temporal 4 timeouts |
| 🔴 P0 | `schemaVersion` 字段 + 协议握手 | 协议演进 | 0.5d | gRPC protobuf versioning |
| 🟡 P1 | `SdkTypedTaskHandler<I, O>` 泛型基类 + JSON 反序列化 | 类型安全 | 1d | Temporal/Zeebe @ActivityInterface |
| 🟡 P1 | `batch-worker-sdk-testkit` 子模块 + FakeBatchPlatform | 测试基建 | 3d | Temporal TestWorkflowEnvironment |
| 🟡 P1 | Worker fingerprint(buildId / sdkVersion / hostName / pid) | 部署运维 | 0.3d | Temporal worker identity |
| 🟡 P1 | Payload codec(加密) | 合规 | 1d | Temporal DataConverter |
| 🟡 P1 | OTel context propagation(`traceparent`) | 可观测性 | 0.5d | 业界标准 |
| 🟡 P1 | 控制 topic push(cancel / shutdown / pause) | 实时控制 | 1.5d | Temporal heartbeat response |
| 🟢 P2 | JSON Schema 校验 task input | 健壮性 | 1d | Conductor |
| 🟢 P2 | Rate limit per workflow/taskType | 滥用防护 | 1d | Temporal namespace limit |
| 🟢 P2 | 金丝雀 routing by buildId | 灰度发布 | 1d | Temporal versioning |

---

## 7. 路线图建议(分季度)

### 2026 Q3:长任务可控性补齐(P0 全做完)
- Heartbeat with details
- Cancel push
- 多种 timeout
- schemaVersion 协议握手

### 2026 Q4:租户开发体验(P1 主体)
- 类型安全 handler
- testkit 子模块
- Worker fingerprint
- OTel context

### 2027 Q1:企业级合规(P1 剩余)
- Payload codec(加密)
- 控制 topic push(cancel/shutdown/pause)

### 2027 Q2+:高级特性(P2)
- JSON Schema 校验
- Rate limit / quota
- 金丝雀 routing

---

## 8. 不应被对标拖入的反模式

对标 ≠ 全盘照搬。以下业界做法对我们**反而是错的**:

| 业界做法 | 为什么我们不该学 |
|---|---|
| Temporal 把 workflow 写在 worker 端 | 破坏"orchestrator 是唯一状态主机"红线 |
| Conductor 让 worker 长 poll HTTP | Kafka 派单对 batch 场景更优(可重放 / 审计) |
| Zeebe gRPC streaming | 引入 protobuf 依赖,SDK 2 MB → 15 MB,违反最小依赖原则 |
| Temporal 引入 OTel 强依赖 | 强依赖会拖死租户进程,我们保持 optional |
| AWS SFn 把 worker 锁在 AWS account | 我们多云 / 私有云租户都要支持 |

**核心**:学协议设计、学异常处理、学测试基建,**不学**架构形态和强依赖。

---

## 9. 维护

- 业界每年大更新一次(Temporal 跟 Zeebe 半年一个 minor),需要**每年 review 本文档一次**
- 新增对标项(比如 Temporal 出新版本加了某能力)→ 加到矩阵 §2 + 评估章节
- 实施完的项 → 划掉对应行,review doc 同步更新

---

**参考**

- [Temporal Java SDK](https://github.com/temporalio/sdk-java)
- [Zeebe Java client](https://docs.camunda.io/docs/apis-tools/java-client/)
- [Conductor Java client](https://github.com/conductor-oss/conductor)
- [AWS Step Functions Activity Workers](https://docs.aws.amazon.com/step-functions/latest/dg/concepts-activities.html)
- [worker-deployment-models](./worker-deployment-models.md)
- [batch-worker-sdk 深度评估](../review/batch-worker-sdk-deep-review-2026-05-31.md)
