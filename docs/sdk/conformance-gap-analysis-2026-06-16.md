# BYO SDK conformance 契约 — 覆盖缺口分析与补强计划

> 2026-06-16。对标业界(gRPC interop / OpenID conformance / AWS Smithy / Stripe 混合派)审视多语言 BYO SDK
> 的 conformance 套件,定位**哪些 wire-protocol 不变量已被跨语言机器断言、哪些只活在文档/服务端测试**,给出补强计划。
> 相关:PR #524(BYO SDK 防漂移契约)、`docs/sdk/byo-conformance-contract.md`、`docs/api/sdk-contract-fixtures/`。

## 1. 业界范式(为什么这么做)

规模化做多语言 SDK 的公司都不靠"各语言手写后人肉对齐",靠**单一真相源 + 生成/校验 + 跨语言一致性测试套件**:

- **真相源单一**:AWS Smithy、Google Protobuf+AIP、Stripe/Azure OpenAPI、Kubernetes OpenAPI——SDK 是 spec 的投影。
- **生产模式**:全生成(一致但不地道)/ 全手写(必漂移)/ **混合(生成核 + 手写人体工学层,主流)**。本项目是混合派(决策核 + 防漂移契约)。
- **防漂移四道防线**:① spec lint(spectral/buf lint/api-linter)② breaking-change gate(oasdiff/buf breaking)③ **跨语言一致性 conformance suite**(gRPC interop、OpenID/FAPI conformance、CommonMark/JSON-Schema test-suite、Pact)④ golden/snapshot。
- **协议约束固化**:schema registry(Confluent/Buf)强制兼容策略;兼容规则成文+自动检查;**语义(幂等键/重试/分页/错误分类)写进 SDK 默认行为而非文档**。

本项目已踩在混合派正道上(领先很多团队),本文聚焦第 ③ 道防线的**覆盖缺口**。

## 2. 现状:conformance 套件覆盖什么

**结构**(`docs/sdk/byo-conformance-contract.md`):三条强制约束 = ①常量从 `sdk-shared-constants.yaml` consume(禁手写)+ parity 测试;②跑全部 `sdk-contract-fixtures/*.json` 的 `then.expect`;③接入 `sdk-contract-parity.yml` CI。

**CI 门**(`.github/workflows/sdk-contract-parity.yml`):硬门 `validate-fixtures` + `java/typescript/go/rust-contract`(任一红阻断);软门 `python-contract`(xfail)、`parity-report`(log-only)。

**runner 是异构的**(关键):
- **Java**(`JsonFixtureContractTest`):静态契约校验——fixture 结构 + `(method,path)` 必须在 `orchestrator-internal.openapi.yaml` 已声明。**不跑决策逻辑、不校请求体**。另有 `SdkPlatformContractTest`/服务端 `SdkWireContractTest` 测真实 SDK 行为(但非共享 fixture)。
- **TS/Go/Rust**(`decide.ts` 等决策核):把 `when`(响应 status/body)映射到一个纯 `Decision`,断言 `then.expect`。**从不读 `when.body`(SDK 发出的请求),不校出向 payload**。
- **Python**:contract runner(软门 xfail)。

**`then.expect` 闭集 16 字段**(`fixture-schema.json:93-157`,`additionalProperties:false`)**全是"响应→反应"维度**:`action`(8 枚举:register-online/apply-directive/fail-fast/idempotent-success/retry-then-drop/cancel/backpressure/graceful-stop)、`retry`/`retryBackoffMs`/`maxAttempts`/`failFast`、`fsmTransition`、`kafka`、`startSchedulers`、`heartbeatNextIntervalMs`、`cancelRequested`/`idempotent`/`reportFailure`/`deactivate`/`drainThenDeactivate`/`resumeWhenDrained`/`withinMs`。

**12 个 fixture 覆盖**:注册上线/幂等、心跳 directive→FSM(NORMAL/PAUSED/DRAINING)、心跳 interval hint、错误码分类(401 fail-fast / 409 idempotent / 5xx 退避 [200,400,800]×3)、renew cancel、Kafka 背压、graceful stop。

## 3. 结构性根因

`then.expect` 闭集**没有任何"SDK 发出的请求体/请求头"维度**;决策核只按 `when.path` 路由,**从不校验 SDK 实际构造的出向 payload**。`when.body` 当前仅作文档,不被断言。

**后果**:最致命的协议不变量——**partitionInvocationId 贯穿 claim→renew→report**(R3-P1-10;PR #493 双跑根因)、**report 字段名红线**(`outputs`/`errorCode`/`success:bool`)——只活在文档 + Java/Python 各自的服务端测里,**Go/TS/Rust 三个新语言的 conformance 完全测不到**。一个新语言 SDK 把这两条实现错,5 个 contract job **照样全绿、照样上线、照样双跑/静默丢字段**。

## 4. 缺口矩阵(排序 = 协议正确性风险 × 跨语言漂移概率)

| # | 不变量 | 出处 | fixture 已断言 | 现仅测于 | 建议补的断言点 |
|---|---|---|---|---|---|
| **P0-1** | partitionInvocationId 贯穿 claim→renew→report | R3-P1-10/P0-5;`LeaseRenewalScheduler:92-95`、`TaskDispatcher:79-80,278-282`;server `DefaultTaskOutcomeService:328-334` CAS | 否 | Java SDK + 服务端 CAS | 前置 schema 加请求体断言;renew/report 出向 body **必含**与 claim 同一 `partitionInvocationId`(从 dispatch state 回填) |
| **P0-2** | report 字段名红线:`outputs`(非output)/`errorCode`(非errorClass)/`success:bool`(非status串)/`resultSummary` | wire-protocol §B;`SdkWireContractTest:290-335`;`SdkPlatformContractTest:144-152` | 否 | Java/Python 各自(Go/TS/Rust 无对位) | report 出向 body 含 `success(bool)/taskId/tenantId/workerId/outputs`,**不含** `output/errorClass/errorMessage/status` |
| **P0-3** | 未知 schemaVersion(v3+)reject 不 commit offset;缺字段当 v1 | wire-protocol §A;`TaskDispatchMessage.SUPPORTED_MAJOR_VERSIONS` | 否(决策核有函数无 fixture) | 决策核单测 | 3 kafka fixtures:缺失→accept(v1)、v2→accept、v3→reject(`then.expect` 加 `schemaAccept:bool` 或复用 `drop-message`) |
| **P1-4** | 非401/409 的 4xx 累计 5 次 fail-fast(防活锁) | wire-protocol §B/C `clientErrorFailFastThreshold=5` | 否 | 决策核单测 | claim 返 400/422 → `client-error,retry:false,failFast:false`;第 5 次 → `failFast:true`(需 `given.state.clientErrorCount`) |
| **P1-5** | 404 放弃(非fail-fast非retry);renew 持续404→fail-fast | wire-protocol §B/§3 | 否 | 决策核 not-found 分支 | renew 404 → `not-found,retry:false,failFast:false`(`action` enum 需扩 `not-found`) |
| **P1-6** | idempotency-key 每次写操作独立(非固定 report-{taskId}) | Python P0-3;Java key=`sdk-<uuid4>` | 否 | Python+Java 自测 | report 断言 `Idempotency-Key` header 匹配 `<lang>-<uuid4>` 且多次互不相同(需请求头断言) |
| **P1-7** | register 禁把 apiKey 放 body(走 Authorization header) | fixture 01 `sdkMustNot`(prose) | 否 | prose | register body **不含** apiKey;`Authorization` header 存在(安全,新语言易漏) |
| **P1-8** | register/claim/report 走完整 retryMaxAttempts;heartbeat/renew 单次失败等下一 tick(不内部退避) | wire-protocol §C 豁免 | 部分(09 测 report 退避,未测 heartbeat/renew 不退避反例) | 文档 | heartbeat 503 → `retry:false`/`maxAttempts:1` 锁定豁免 |
| **P2-9** | DEGRADED FSM 态 | wire-protocol §2.1;enum 有 | 部分(enum 在无 fixture) | 决策核 | heartbeat `platformStatus:DEGRADED` → `fsmTransition:DEGRADED` |
| **P2-10** | `desiredMaxConcurrent` 动态压并发 | §2.1;fixture 03 body 该字段为 null | 否 | 文档+Java | heartbeat `desiredMaxConcurrent:2` → 断言生效(需 `effectiveMaxConcurrent`) |
| **P2-11** | `pausedTaskTypes` 命中 → drop 不 commit offset | §2.1;fixture 05 prose 写了但只断 PAUSED | 部分 | prose+Java | 命中 → `kafka:drop-message`(enum 已有该值) |
| **P2-12** | DTO ignoreUnknown(平台加新字段旧SDK不崩) | §A;`SdkPlatformContractTest:74` | 否 | server | kafka body 注入未知字段 → SDK 仍正常 dispatch |

## 5. 补强设计(前置:加"出向请求"断言维度)

当前闭集最大结构缺口 = 无请求侧断言。前置 schema bump(`fixture-schema.json` `expect` 闭集)新增:

```jsonc
"requestBodyIncludes": { "type": "object" },   // 出向 body 必含这些 key/value(深含)
"requestBodyExcludes": { "type": "array", "items": {"type":"string"} }, // 必不含这些 key
"requestHeaders":      { "type": "object" },   // 出向 header 必含(值可用正则前缀)
"schemaAccept":        { "type": "boolean" }   // kafka 消息按 schemaVersion 接受/reject
```

并扩 `action` enum 增 `client-error` / `not-found`(P1-4/P1-5)。partitionInvocationId 贯穿(P0-1)用 `given.state` 携带 claim 阶段存的 `partitionInvocationId`,决策核回填到 renew/report 的请求体,fixture 用 `requestBodyIncludes` 断言。

**runner 适配(异构,逐语言)**:决策核(TS/Go/Rust)扩展为对 renew/report/register 场景**额外产出它将构造的 request**(body+headers),runner 把它与 `expect.requestBody*`/`requestHeaders` 比对;Java 静态校验器读新字段做结构校验 + 复用既有 `SdkWireContractTest` 的真实出向断言。新字段全 optional,旧 fixture 不受影响。

## 6. 落地顺序(增量、每步 parity CI 绿)

1. **增量 1(P0 结构)— ✅ 已落(PR #530/#531):** schema bump(请求体/请求头断言 + `client-error`/`not-found` enum) + fixtures P0-2(report 字段名)、P0-1(partitionInvocationId 贯穿)、P0-3(schemaVersion reject) + 5 语言 runner 适配请求侧断言。**这一刀堵住最致命的跨语言漏网。**
2. **增量 2/3(P1+P2)— ✅ 已落(本 PR,2026-06-16):** 合并施工。fixtures 23–29 + schema 加 `effectiveMaxConcurrent`:
   - P1:23 4xx 累计第 5 次 fail-fast(与 21 成对)/ 24 idempotency-key 独立 mint(`^[a-z-]+[0-9a-f]{8}-[0-9a-f-]{8,}$`,前缀类放宽兼容 Python `sdk-py-`)/ 25 heartbeat-renew 不退避(§C 豁免)。404 放弃(22)、apiKey-header(19)已在增量 1 覆盖,本轮 Python 一并转硬。
   - P2:26 DEGRADED / 27 desiredMaxConcurrent→effectiveMaxConcurrent / 28 pausedTaskTypes drop / 29 ignoreUnknown。
   - 各语言接法:**TS/Go/Rust** 决策核加 `classifyHeartbeatRenewError`+`decidePausedTaskType`、`applyHeartbeatDirective` 补 DEGRADED + effectiveMaxConcurrent、`classifyHttp` 累计阈值;**Java** `JsonFixtureContractTest` 静态全覆盖 29 条;**Python 请求侧 + 响应侧分类转硬**(真实 `PlatformHttpClient` + typed exception + fail-fast 阈值,不再 skip)。
   - **仍留后续(仅 Python,strict xfail,parity 不红)**:25(heartbeat 复用通用 `with_retry`,缺 §C no-backoff 分支)、28(`apply_platform_directive` 未在 `on_message` 按 pausedTaskTypes drop)。两条均为 production 行为变更,超出 conformance 增量范围;TS/Go/Rust/Java 对这两条已硬断言。详见 `byo-conformance-contract.md` §2.1.1。

> 约束:5 语言 parity CI 同时门禁,新字段必须各 runner 同步支持(Java 静态 + TS/Go/Rust 决策核 + Python 真实断言),否则断言形同虚设或 CI 红;Rust 本机构建受限(见记忆),靠 CI 跑(本轮 Rust 最小正则匹配器扩 `[a-z-]` 类已用等价 Python 端口仿真验证)。本地已验 Java/TS/Go/Python 全绿。

## 7. 一句话判断

当前 conformance 把"SDK 对**响应**的反应"守得不错,但**没有任何"SDK 发出的请求"维度**——最致命的 partitionInvocationId 贯穿(双跑根因)和 report 字段名红线,三个新语言完全测不到。补上请求侧断言维度 + P0 三条,才真正"挡得住一个新语言 SDK 把协议实现错"。
