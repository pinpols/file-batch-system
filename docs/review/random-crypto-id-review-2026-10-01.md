# 随机数、加密算法与 ID 生成审查（2026-10-01）

## 结论

本次审查范围覆盖后端生产代码、对象存储加密、Console 登录与 JWT、内部请求签名、API Key、五语言 SDK 的 nonce / idempotency-key 生成，以及脚本中与临时凭据相关的随机数使用。审查后已完成本地可整改项。

总体结论：

- 服务端主链路没有发现明显的算法选型错误。API Key、对象加密、JWT、HMAC 请求签名均采用现代可接受方案。
- 生产 Java 代码未发现把 `Math.random()` 用于安全 token、密钥或认证流程。
- 当前没有 Snowflake 实现，也没有 workerId / datacenter / sequence 位分配配置。因此不存在 Snowflake 时钟回拨类活 bug；若未来引入，应先做独立设计。
- 原主要风险集中在 SDK 侧随机 ID 质量、业务编号碰撞余量、KMS key 启动期校验和请求签名 header 输入边界；本轮已完成对应修复。

## 修复状态

| ID | 主题 | 状态 |
|---|---|---|
| CRYPTO-ID-1 | TypeScript SDK `Idempotency-Key` 从 `Math.random()` 改为 `node:crypto.randomUUID()` | ✅ 已修 |
| CRYPTO-ID-2 | Rust SDK idempotency-key / signing nonce 从时间种子 xorshift 改为 `getrandom` CSPRNG | ✅ 已修 |
| CRYPTO-ID-3 | `IdGenerator.newBusinessNo` 后缀从 8 hex 扩到 16 hex，并修正批量注释/测试 | ✅ 已修 |
| CRYPTO-ID-4 | KMS 启动期校验 `defaultKeyRef` 存在性与 AES key 长度，生产继续拒绝全零占位 key | ✅ 已修 |
| CRYPTO-ID-5 | 请求签名 header 增加 timestamp / nonce / signature 格式和长度边界 | ✅ 已修 |
| CRYPTO-ID-6 | Go SDK 处理 `crypto/rand.Read` 错误，失败时 fail-fast | ✅ 已修 |

## 验证记录

- `./mvnw -pl batch-common,batch-orchestrator -DskipITs -DskipE2E -DskipTests=false -Dtest='IdGeneratorTest,BatchCommonAutoConfigurationConditionTest,RequestSignatureVerifierTest' test`
- `./mvnw -pl batch-common,batch-orchestrator spotless:check -DskipTests -DskipITs -DskipE2E`
- `cd sdk/typescript && npm run build && npm test`
- `cd sdk/typescript && npm run format:check -- --log-level warn`
- `cd sdk/go && go test ./...`
- `cd sdk/rust && cargo test`
- `cd sdk/rust && cargo test --features http`
- `cd sdk/rust && cargo fmt --check`
- `python3 scripts/ci/check-docs-structure.py && python3 scripts/ci/check-doc-timestamp-policy.py && python3 scripts/ci/check-code-doc-references.py`

## 审查范围

主要入口：

- `batch-common/src/main/java/io/github/pinpols/batch/common/security/`
- `batch-common/src/main/java/io/github/pinpols/batch/common/service/BatchObjectCryptoService.java`
- `batch-common/src/main/java/io/github/pinpols/batch/common/storage/EncryptingObjectStore.java`
- `batch-common/src/main/java/io/github/pinpols/batch/common/utils/IdGenerator.java`
- `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/`
- `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/security/`
- `sdk/{java,go,python,typescript,rust}/`
- `scripts/lib/sdk-e2e-common.sh`

排除项：

- 测试 fixture 中的随机后缀不作为生产漏洞结论。
- 文档、锁文件、依赖校验 hash 不作为加密实现审查对象。

## 已确认合理的设计

### API Key

新签发 API Key 使用 `SecureRandom` 生成 32 字节原始 key，并存储 PBKDF2-HMAC-SHA256 派生结果。

证据：

- `ConsoleApiKeyService.generateRawKey()` 使用 `SecureRandom`。
- `ApiKeyHasher` 使用 `PBKDF2WithHmacSHA256`、每 key 16 字节 salt、600k 迭代。
- legacy `sha256` 仅用于老数据兼容验证，新签发路径不使用。

评价：设计方向正确。后续重点是清理 legacy 行和观察 PBKDF2 验证性能，而不是更换算法。

### 对象存储加密

对象加密使用 AES-GCM：

- 12 字节随机 IV。
- 128 bit tag。
- 加密产物携带 magic、version、keyRef、IV。
- `EncryptingObjectStore` 已使用 `ExactSizeInputStream.exactAndBounded`，不再只信任调用方声明的 size。

评价：算法与完整性方向正确。启动期 key 长度和默认 key 引用校验已补齐。

### Console 登录加密与 JWT

Console 登录加密使用 RSA-OAEP-SHA256 包装 AES-GCM key，服务端显式设置 MGF1 SHA-256，避免 JDK 默认 MGF1-SHA1 与 Web Crypto 不一致。

JWT 使用 HS256，生产 profile 下拒绝空 secret、默认占位和长度不足 32 字符的 secret。

评价：符合当前系统定位。登录请求加密不能替代 HTTPS，但作为前端登录明文保护的纵深措施是合理的。

### 请求签名

内部请求签名采用：

```text
canonical = UPPER(method) "\n" path "\n" timestamp "\n" nonce "\n" hex(sha256(body))
signature = hex(hmacSha256(apiKey, canonical))
```

服务端先校验 timestamp 和 HMAC，再通过 Redis `SETNX + TTL` 登记 nonce，避免错误签名请求污染 nonce 空间。

评价：协议方向正确。header 长度和格式约束已补齐。

## 发现与修复

### P1：TypeScript SDK idempotency-key 使用 `Math.random()`

位置：

- `sdk/typescript/src/decide.ts`

问题：

`newIdempotencyKey()` 依赖本地 `randomUuid()`，而 `randomUuid()` 使用 `Math.random()` 拼 UUID。该 key 用于 claim/report 等写请求的幂等键，不应使用非安全随机。虽然 TypeScript HTTP transport 签名 nonce 已使用 `node:crypto.randomUUID()`，但 decide fallback 仍是弱随机。

影响：

- 并发或长时间运行下碰撞概率高于标准 UUID v4。
- 不符合五语言 SDK 对“每次写请求新鲜幂等键”的生产契约。

修复：

- 已改为 `node:crypto.randomUUID()`。
- 现有 TypeScript SDK build / conformance test 已通过。

### P1：Rust SDK idempotency-key / nonce 使用时间种子 xorshift

位置：

- `sdk/rust/src/decide.rs`
- `sdk/rust/src/client/reqwest_transport.rs`

问题：

Rust SDK 为了 std-only，使用系统时间作为种子，再用 xorshift 生成 uuid-shaped 字符串。该逻辑用于 idempotency-key，并复用于请求签名 nonce。

影响：

- 多进程、同机快速启动、时间分辨率不足或系统时间异常时，碰撞和可预测性风险明显高于 OS CSPRNG。
- 请求签名 nonce 虽然由 HMAC 绑定，但防重放依赖 nonce 一次性，仍应使用高质量随机。

修复：

- 已引入轻量 `getrandom`，由 OS CSPRNG 生成 UUID v4。
- 默认 `cargo test` 与 `cargo test --features http` 均已通过。

### P1：业务编号后缀只有 8 hex，容量边界偏薄

位置：

- `batch-common/src/main/java/io/github/pinpols/batch/common/utils/IdGenerator.java`

问题：

`newBusinessNo(prefix)` 的格式为：

```text
prefix-yyyyMMddTHHmmssZ-xxxxxxxx
```

随机后缀只有 8 位 hex，即 32 bit。该方法用于 `instance_no`、`request_id`、审批号、补偿命令号等业务编号。部分表上存在唯一约束，碰撞会转化为失败或重试。

影响：

- 同前缀、同秒内生成量上升时，碰撞概率不再可以忽略。
- `newBusinessNoBatch` 注释写“后缀按行号唯一化”，但实现仍是随机后缀，注释与代码语义不一致。

修复：

- 后缀已扩到 16 hex。
- `newBusinessNoBatch` 注释和 `IdGeneratorTest` 已同步。

### P2：KMS key 启动期校验不足

位置：

- `batch-common/src/main/java/io/github/pinpols/batch/common/config/BatchObjectCryptoAutoConfiguration.java`
- `batch-common/src/main/java/io/github/pinpols/batch/common/service/BatchObjectCryptoService.java`

问题：

生产 profile 当前拒绝空、非法 base64、全零 KMS key，但没有启动期校验：

- AES key 长度必须是 16 / 24 / 32 字节。
- `defaultKeyRef` 必须能在 `keys` map 中解析到有效 key。

影响：

- 错误配置可能延迟到第一次加密/解密时报错。
- 对象加密启用后，启动成功不代表加密路径可用。

修复：

- 所有环境启动期校验 `defaultKeyRef` 存在和 AES key 长度。
- 生产 profile 继续 fail-fast 拒绝全零占位 key。
- 已补 `BatchCommonAutoConfigurationConditionTest`。

### P2：请求签名 header 缺长度和格式边界

位置：

- `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/security/RequestSignatureVerifier.java`
- `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/security/RedisNonceStore.java`

问题：

服务端当前只校验 timestamp、nonce、signature 非空和签名正确，没有限制：

- timestamp 长度和格式。
- signature 是否为 64 位小写 hex。
- nonce 最大长度和字符集。

nonce 会直接进入 Redis key：

```text
sig:nonce:{tenantId}:{nonce}
```

影响：

- 合法签名客户端可提交超长 nonce，放大 Redis key 空间和内存成本。
- 日志、指标和排查工具也可能被异常长 header 干扰。

修复：

- timestamp 限制为 10 到 17 位数字字符串。
- signature 限制为 64 位小写 hex。
- nonce 限制为 8 到 128 位 `[A-Za-z0-9._:-]`。
- 非法 header 在 HMAC 和 Redis nonce 登记前拒绝。

### P3：Go SDK 忽略 `crypto/rand.Read` 错误

位置：

- `sdk/go/protocol/request.go`
- `sdk/go/client/signing.go`

问题：

Go SDK 使用 `crypto/rand.Read` 生成 UUID-shaped 随机值，但忽略返回值和错误。

影响：

- OS CSPRNG 失败极少见，但安全路径不应静默继续。

修复：

- 已增加 `mustReadRandom`，OS CSPRNG 失败时 fail-fast。
- Go SDK `go test ./...` 已通过。

## Snowflake 结论

当前生产代码未发现 Snowflake 算法实现，也没有以下配套配置：

- workerId / datacenterId 分配。
- sequence 位宽。
- epoch 起点。
- 时钟回拨处理。
- 跨副本 workerId 唯一性治理。

因此本轮不建议临时引入 Snowflake。当前系统已经使用数据库自增主键承载内部 PK，用 UUID / 业务幂等键承载跨系统追踪和幂等语义。若未来为了可排序、可读或跨库离线生成 ID 引入 Snowflake，应先形成 ADR，至少回答：

- workerId 如何分配和回收。
- 容器重启、扩缩容、跨机房时如何保证 workerId 唯一。
- 时钟回拨时是阻塞、降级还是切换序列。
- 是否需要暴露 ID 时间语义，以及是否带来业务信息泄露。

## 待办状态

本轮登记到 [`../analysis/todo-master.md`](../analysis/todo-master.md) 的 `G8. 随机数 / 加密 / ID 治理` 已完成。后续若引入 Snowflake、ULID、外部 KMS 或新的 SDK 传输实现，应重新执行本类审查。
