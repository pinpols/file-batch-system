# SDK Troubleshooting — 常见错误自助查

按「症状 → 根因 → 处理」组织。先看症状对号入座;查不到再翻 [ADR-035](../architecture/adr/ADR-035-tenant-self-hosted-worker-sdk.md) 或 [onboarding runbook](../runbook/per-tenant-worker-onboarding.md)。

> 想知道 SDK 怎么跑起来 → [quickstart.md](./quickstart.md)。

---

## 1. worker 起不来

`BatchPlatformClient.start()` 抛 `RuntimeException`,进程 exit non-zero。

| 症状(日志关键字) | 根因 | 处理 |
|---|---|---|
| `missing required env vars: ...` 或 `missing required env: BATCH_API_KEY` | `fromEnv()`(前缀 `BATCH_SDK_`)/ sample 工程的 `requireEnv()`(前缀 `BATCH_`)缺必填项 | 检查容器 env / k8s secret 是否注入;前缀别混用;`API_KEY` P2 之后强制 |
| `register failed: 401` | API key 无效 / 过期 / revoke / scope 不含 `worker.execute` | 见 §2「register 失败」 |
| `org.apache.kafka.common.errors.SaslAuthenticationException` | Kafka SASL/SCRAM 凭据错或 ACL 未授权该 topic | 检查 `KAFKA_SECURITY_PROTOCOL` / `KAFKA_SASL_MECHANISM` / `KAFKA_SASL_JAAS_CONFIG`(对应 sample 里 `BATCH_KAFKA_PROTOCOL` / `BATCH_KAFKA_SASL_MECHANISM` / `BATCH_KAFKA_SASL_JAAS`);onboarding §3 跑 `scripts/data/init-tenant-kafka-acl.sh` |
| `java.net.ConnectException` / `UnknownHostException` 指向 `BASE_URL` | 平台地址不通(域名错 / VPC 不通 / TLS 校验失败) | curl 验通;企业代理走 `-Dhttps.proxyHost` |

---

## 2. register / heartbeat 失败

| 症状 | 根因 | 处理 |
|---|---|---|
| `POST /internal/workers/register → 401` | API key SHA-256 hash 在 `batch.api_key` 不匹配,或 tenantId 跟 key 绑定不一致(ADR-035 §2「鉴权」) | 重新申请 / 轮转 key;**不会** fallback `X-Internal-Secret`(防泄漏冒充) |
| `409 conflict` 同名 taskType | 同 tenantId 已有另一 worker 注册同 `taskType` descriptor(不兼容) | 改 `taskType` 命名 / 协调先 deactivate 旧 worker / 走 console 强制反注册 |
| `400 invalid descriptor` | `SdkTaskHandler.descriptor()` JSON schema 不合规(parameter 缺类型 / 枚举值非字符串) | 比对 `custom_task_type_registry` schema(roadmap §M3.1);本地用 testkit 单测 descriptor |
| `heartbeat 429 / timeout` 偶发 | 网络抖动;SDK 默认重试 + 指数退避 | 单次抖动可忽略;持续 → 看下条 |
| `heartbeat 410 gone` / `lease expired` 持续 | **`leaseRenewInterval ≥ server lease TTL`** — lease 还没续就过期了 | 关键值:`leaseRenewInterval` 必须 `< server TTL / 2`。默认 server TTL ~3min,SDK 默认 60s 正常。若你改过,调回 ≤ 60s。详见 ADR-035 §「Scheduler 节奏」 |

---

## 3. task 一直 ready 不跑

handler 没收到 `execute()`,console 看 job 卡在 ready / dispatched。

| 症状 | 根因 | 处理 |
|---|---|---|
| 日志有 `KafkaTaskConsumer paused partition` 不消费 | **Capacity-aware pause** — inFlight 触顶,SDK 主动 pause partition 不抢单 | 跑完手头任务自动 resume;持续 pause 说明 handler 长期停滞(看堆栈)或 `maxConcurrentTasks` 太低。机制见 ADR-035 §11 P0-1 |
| 日志显示 `dispatcher state=PAUSED` 或 `DRAINING` | 平台 heartbeat directive 命令暂停;SDK 拒新任务 | console 检查是否管理员主动 pause 了 worker;DRAINING 意味着即将下线,不可逆。状态机见 ADR-035 §11 P0-2 + `WorkerRuntimeState.java` |
| handler 抛异常但日志没看到栈 | 业务 handler 抛 `RuntimeException`,被 dispatcher catch 转 `SdkTaskResult.fail`,栈走 WARN 级 | 调日志级别到 DEBUG;或在 handler 里 `try/catch` 自己 `log.error("...", e)` 保留完整栈 |
| Kafka topic 看不到消息进来 | topic 命名不匹配 `batch.task.dispatch.{type}.{tenantId}`(ADR-035 §2 Kafka ACL) | 在 platform 端确认 topic 已建 + 消费者订阅模式匹配;`kafkaTopicPattern` 默认 `batch.task.dispatch.<tenantId>.*`,自定义时注意 anchors |

---

## 4. log 噪声

SDK 暂未发布通用 `ThrottledLogger`(在 sdk-roadmap backlog)。临时降级方式:

- logback / log4j2 config 给 `io.github.pinpols.batch.sdk.dispatcher` / `...kafka` 单独提到 `WARN`
- 或加 `TurboFilter` / rate-limit appender(如 `RateLimitingFilter`)
- 热点循环里的 INFO 暂时改 DEBUG;待 SDK 提供官方 throttled logger 再回退

---

## 5. 凭据泄露怀疑(自查清单)

平台对 worker 上行的 payload 走 JSON schema 校验(ADR-035 §3,所有租户输入按 untrusted 处理),但 **handler 不要把凭据放进以下字段**:

- `descriptor()` 返回的 task type 元数据(会持久化到 `custom_task_type_registry`,console 可读)
- `SdkTaskResult` 的 `message` / `details` map(平台 REPORT 持久化,审计查得到)
- heartbeat 的 metric / extras 字段
- 业务参数 `ctx.parameters()` 回显(如 echo 类 handler)

排查路径:

1. 把可疑 payload 在 console「任务执行详情」打开,看是否能肉眼读出凭据
2. 平台侧 grep `batch.outbox_event.payload` / `task_report.details`(运维)
3. 真泄漏 → 立即在 console「我的 Worker」轮转 API key + Kafka SASL 凭据

---

## 6. K8s readiness probe 频繁 timeout

Spring Boot starter 启动比 plain SDK 慢(autoconfigure + bean 扫描):

- `initialDelaySeconds` 拉到 60 ~ 90s
- 或换用 plain `java -jar` entry(不上 starter),起来快
- starter `SmartLifecycle` phase 故意调晚(`Integer.MAX_VALUE - 100`)以等业务 bean 就绪,这是设计(ADR-035 §1.1),非 bug

---

## 找不到症状?

- 看 [ADR-035](../architecture/adr/ADR-035-tenant-self-hosted-worker-sdk.md)「实施记录:协议字段细节」对照 wire payload
- 看 [`docs/runbook/per-tenant-worker-onboarding.md`](../runbook/per-tenant-worker-onboarding.md) §「常见接入问题」
- 平台运维侧:[`docs/runbook/`](../runbook/) 内对应 `kafka-*` / `outbox-*` runbook
