# SDK 配置总表(五语言)

租户自托管 Worker SDK 的**唯一权威配置参考**。概念名 + 权威 env 名 + 必填 / 默认 / 失败模式,五语言(Java / Python / Go / TypeScript / Rust)对齐同一份语义。

> **env 前缀唯一权威 = `BATCH_SDK_`**。Java `BatchPlatformClientConfig.fromEnv()` 与 Python `BatchPlatformClientConfig.from_env()` 均默认此前缀;Go / TS / Rust 的 env 覆盖点(如 `BATCH_SDK_REQUEST_SIGNING_ENABLED`)同样走 `BATCH_SDK_`。样例程序里出现的 `BATCH_*`(如 `BATCH_BASE_URL`)是**样例自己的 env 契约**、由各语言样例 `main` 自行映射进 builder,不是 SDK 权威前缀 —— 生产接入以本表 `BATCH_SDK_*` 为准。

## 权威配置项

| 概念 | 类型 | 必填 | 默认 | 权威 env(`BATCH_SDK_` 前缀) | 失败模式 |
|---|---|---|---|---|---|
| baseUrl | URL | 是 | — | `BASE_URL` | 缺失 → 构造抛错;尾斜杠 → 校验抛 `IllegalArgumentException` |
| apiKey | string | 上线必填(P1 可空) | null | `API_KEY` | 空且服务端要鉴权 → CLAIM 401 → dispatcher fatal;**禁日志** |
| tenantId | string | 是 | — | `TENANT_ID` | 缺失 → 构造抛错;跨租户消息 → drop 不提交 offset |
| workerCode | string | 是 | — | `WORKER_CODE` | 缺失 → 构造抛错;同租户内需唯一 |
| kafkaBootstrap | string | 是(消费派单) | null | `KAFKA_BOOTSTRAP` | 缺失 → 不建消费者(仅 HTTP);错值 → 消费启动失败 |
| kafkaTopicPattern | regex | 否 | node-direct 派生 | `KAFKA_TOPIC_PATTERN` | 默认 `batch\.task\.dispatch\..+\.node\.<workerCode>`;写成 tenant-first `<tenant>.*` → **静默收不到任务**(平台从不发布) |
| kafkaGroupId | string | 否 | `g-sdk-<tenant>-<workerCode>` | `KAFKA_GROUP_ID` | 同 group 跨进程自动分片 |
| buildId | string | 否 | null | `BUILD_ID` | 运行指纹(建议 CI 注入 git SHA);**禁放敏感信息**(入库对运维可见) |
| maxConcurrentTasks | int | 否 | **4** | `MAX_CONCURRENT_TASKS` | 范围 1..64(Java 校验);超出并发走 capacity-aware pause |
| heartbeatInterval | 时长 | 否 | **30s** | `HEARTBEAT_INTERVAL_SECONDS` | 服务器 `nextHeartbeatHintMs` 钳制覆盖;< 1s → strict 时 fail-fast |
| httpTimeout | 时长 | 否 | **10s** | `HTTP_TIMEOUT_SECONDS` | connect + read 合一;> heartbeat/2 → strict 时 fail-fast |
| leaseRenewInterval | 时长 | 否 | 60s | `LEASE_RENEW_INTERVAL_SECONDS` | 应 < orchestrator lease TTL 的 1/2;> heartbeat×3 → strict 时 fail-fast |
| kafkaSecurityProtocol | string | prod 必填 | null | `KAFKA_SECURITY_PROTOCOL` | 如 `SASL_SSL`;缺失走 `PLAINTEXT` |
| kafkaSaslMechanism | string | prod 必填 | null | `KAFKA_SASL_MECHANISM` | 如 `SCRAM-SHA-512` |
| kafkaSasl 凭据 | string | prod 必填 | null | Java: `KAFKA_SASL_JAAS_CONFIG` · Python: `KAFKA_SASL_USERNAME` + `KAFKA_SASL_PASSWORD` | SASL 鉴权失败 → fast-fail 不重试 → K8s liveness 重启;**禁硬编码**(K8s Secret / env 注入) |
| requestSigningEnabled | bool | 否 | false | `REQUEST_SIGNING_ENABLED`(`true/1/yes/on` 开) | 开启后写请求带 HMAC 签名;apiKey 空则即便开也不签 |
| strictTimingValidation | bool | 否 | true | `STRICT_TIMING`(`false/0/no/off` 降级) | true=时序 4 规则违反即 fail-fast;false=WARN-only |
| claim/retry 退避 | int/时长 | 否 | 见下 | 见「语言差异」 | 5xx / 传输错误指数退避;连续 4xx(非鉴权非 409)达阈值 → fatal |

## 语言差异(收口待办)

现状 `BATCH_SDK_` 前缀已是五语言共同权威;但**部分具体 env 名 / 覆盖点在各语言尚未逐项统一**,接入时以对应语言 SDK 实际读取名为准:

| 概念 | Java env | Python env | 备注 |
|---|---|---|---|
| SASL 凭据 | `KAFKA_SASL_JAAS_CONFIG`(整段 JAAS) | `KAFKA_SASL_USERNAME` + `KAFKA_SASL_PASSWORD`(拆分) | 语义等价,形态不同 |
| 重试次数 | (fromEnv 未开放;用 builder `claimMax5xxRetries`) | `RETRY_MAX_ATTEMPTS` | Java 走 builder,Python 走 env |
| 重试退避 | (builder `claimRetryBaseDelay`) | `RETRY_BASE_DELAY_MS` | 同上 |
| 时序严格度 | `STRICT_TIMING` | (未开放 env,用构造参数) | Java 有 env 降级口 |

- **Go / Rust 集成读裸 `KAFKA_BOOTSTRAP`**(无 `BATCH_SDK_` 前缀,见 `sdk/rust/src/kafka.rs`):属跨语言 env 漂移,收口由 Go / Rust SDK owner 处理(本页 Java/Python 侧已对齐 `BATCH_SDK_`)。
- 逐项 env 名的五语言完全统一(含 SASL 拆分 vs JAAS、retry env 开放面)列为 follow-up;本页先确立**唯一前缀权威 + 概念级语义对齐**,避免误接入。

## 关联

- Java 配置字段逐项:`sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/client/BatchPlatformClientConfig.java` + `sdk/java/core/README.md` §配置项一览
- Python:`sdk/python/src/batch_worker_sdk/client/config.py`(`from_env`)
- 协议 / 失败感知矩阵:`docs/sdk/wire-protocol.md`
- Spring Boot starter 属性:`batch.worker-sdk.*`(见 `BatchWorkerSdkProperties`)
