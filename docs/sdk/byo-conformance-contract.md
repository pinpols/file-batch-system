# BYO SDK Conformance Contract（防漂移契约)

> **目的**:把"一个 BYO(Bring-Your-Own)SDK 算不算合规"从口头约定变成**机器可断言的硬契约**。
> 任何语言实现(Java / Python / **Go / TypeScript / Rust** / .NET …)要被认作平台兼容,必须满足本文 §1 的四条**强制约束**。
> 这是 ADR-035 Lane P(drift guard)的**单一权威源**,新增语言前先读本文。

## 0. 为什么需要这份契约

`wire-protocol.md` 是人读权威,`then.sdkExpectedAction` 是中文 prose——**多语言各自解读 prose = 行为天然漂移**。
本契约用两个机器化锚点消除这个洞:

1. **常量**锚在 [`docs/api/sdk-shared-constants.yaml`](../api/sdk-shared-constants.yaml)(各语言 **consume,禁 re-author**)。
2. **行为**锚在 [`docs/api/sdk-contract-fixtures/`](../api/sdk-contract-fixtures/) 每个 fixture 的 `then.expect`**结构化字段**(各语言断言同一组离散字段,不解读 prose)。

## 1. 四条强制约束(不满足 = 不合规)

### 1.1 常量必须从 `sdk-shared-constants.yaml` 生成,禁手写

`schema_versions_supported` / `worker_runtime_states` / `sensitive_keywords` / `task_statuses` 等跨语言常量,
SDK 侧**只能 consume 该 YAML**(codegen 或运行时加载),**严禁在各语言源码里重新声明字面量**。
每语言必须有一个 parity 测试,断言其常量产物 == YAML(对照 Java `SharedConstantsParityTest`)。

> Authority order(见 YAML 头注释):Java enum → 该 YAML → 其他语言 consume。改值只能从 Java 起,YAML 跟,其他语言自动同步。

### 1.2 必须通过全部 contract fixtures 的 `then.expect`

每语言必须有一个 **contract runner**,加载 `sdk-contract-fixtures/*.json`,按 `given/when` 驱动 SDK,
断言 `then.expect` 的**每个出现的字段**(见 §2 字段语义)。`then.sdkExpectedAction` 仅作人读注释,**不作断言依据**。
覆盖必须 100%(当前 30 个场景,以 `sdk-contract-fixtures/` 目录实际 fixture 数为准,数量随协议演进增长)。

### 1.3 必须接入 `sdk-contract-parity.yml` CI

新语言 SDK 落地时,必须:
- 把其目录加进 `.github/workflows/sdk-contract-parity.yml` 的 `paths:` 触发器;
- 增加一个 `<lang>-contract` job 跑该语言的 runner + parity 测试;
- 接入 `parity-report` 的 N 语言 pass-set 对账。

### 1.4 必须通过 live transport 接通门禁

Fixture / mock 只能证明决策核不漂移,不能证明生产 transport 可用。每种正式 SDK 必须接入
`sdk-live-transport` 门禁,至少覆盖:

- 真实 Kafka broker 派单消费、手动 offset 提交 / 不提交语义;
- 真实 HTTP control-plane 调用,至少 `claim → report` 能打到 fake orchestrator;
- worker identity / tenant / idempotency / partitionInvocationId 等 header/body 红线在真实 transport 上不漂移。

当前执行入口:

```bash
KAFKA_BOOTSTRAP=localhost:19092 bash scripts/ci/run-sdk-live-transport-gate.sh
```

Java 使用 `FakeBatchPlatform`(EmbeddedKafka + JDK HttpServer)跑完整
`client.start → dispatch → handler → report`;Python 使用 live Kafka + aiohttp
`FakeBatchPlatform` 跑 `dispatch → claim → execute → report`;TypeScript / Go / Rust 在同一
gate 内同时硬跑 live Kafka adapter 与本语言 lifecycle / HTTP transport 测试,至少覆盖
accept / drop / reject、claim、report、Idempotency-Key、租户 header/body。新增语言不得只接
fixture/mock。

## 2. `then.expect` 字段语义(runner 断言映射表)

每个 fixture 的 `then.expect` 只用以下**闭集字段**(schema `additionalProperties:false` 强约束,加字段要 bump schema):

| 字段 | 类型 | runner 必须断言 |
|---|---|---|
| `action` | enum | SDK 触发的主行为:`register-online` / `apply-directive` / `fail-fast` / `idempotent-success` / `retry-then-drop` / `cancel` / `backpressure` / `graceful-stop` |
| `retry` | bool | 该调用是否重试(`false` = 一次定生死,如 401/409) |
| `retryBackoffMs` | int[] | 重试间隔序列必须**精确等于**此数组(如 `[200,400,800]`,指数退避) |
| `maxAttempts` | int | 最大尝试次数(含首发) |
| `failFast` | bool | 置 dispatcher fatal、后续 onMessage 拒收 |
| `fsmTransition` | enum/null | worker 状态机目标态:`NORMAL`/`DEGRADED`/`PAUSED`/`DRAINING`,null = 不变 |
| `kafka` | enum | consumer 动作:`none`/`subscribe`/`pause`/`resume`/`wakeup`/`drop-message`/`commit-skip`。`drop-message`=跳过**不提交** offset(可重投:paused/schema-reject/foreign-tenant);`commit-skip`=跳过**并提交** offset(不可恢复的 decode poison,fixture 30) |
| `startSchedulers` | string[] | 启动的调度器:`heartbeat` / `leaseRenew` |
| `heartbeatNextIntervalMs` | int | 应用 `nextHeartbeatHint` 后的有效心跳间隔(注意各语言 clamp 边界须一致) |
| `cancelRequested` | bool | 经 renew 响应触发 CancellationSignal |
| `idempotent` | bool | 平台幂等(重复 register / 409 claim)按成功处理 |
| `reportFailure` | bool | 该场景是否上报 task FAILURE |
| `deactivate` | bool | 调 `POST /internal/workers/{code}/deactivate` |
| `drainThenDeactivate` | bool | drain 在手任务后再 deactivate |
| `resumeWhenDrained` | bool | in-flight 降回后 resume Kafka assignment |
| `withinMs` | int | 动作必须在此墙钟上界内完成(如 stop 超时) |

> **断言规则**:runner 只断言 fixture 里**出现**的字段(缺省 = 该场景不约束此维度)。所有出现的字段都必须满足。

### 2.1 请求侧断言字段(2026-06-16 增量,§5 补强)

上表全是"响应→反应"维度。新增 4 个 optional 字段覆盖**SDK 发出的出向请求**(请求体 / 请求头 / kafka schemaVersion 处理),堵住 partitionInvocationId 贯穿(双跑根因)与 report 字段名红线在 Go/TS/Rust 测不到的洞:

| 字段 | 类型 | runner 必须断言 |
|---|---|---|
| `requestBodyIncludes` | object | SDK 出向请求 body **深含**这些 key/value(子集匹配,允许多余 key)。锁 report 字段名红线(`success:bool`/`outputs`)、partitionInvocationId 回填 |
| `requestBodyExcludes` | string[] | 出向 body **必不含**这些 top-level key(如废名 `output`/`errorClass`/`errorMessage`/`status`,或属于 header 的 `apiKey`) |
| `requestHeaders` | object | 出向 header 必含每个 key,值当**正则**对实际 header 全匹配(如 `Idempotency-Key` 形态、`X-Batch-Api-Key`) |
| `schemaAccept` | bool | kafka-only:按 `schemaVersion` 是否 accept(§A)。`true`=处理;`false`=reject 且不 commit offset(未知 major v3+) |

**异构 runner 适配**(同一组 fixture,逐语言):
- **TS / Go / Rust**:决策核新增 `buildRequest`/`BuildRequest`/`build_request`,从 `given.config` + `given.state.request` 构造出向 body+headers(字段名 + NON_NULL 省略对齐平台 wire DTO;apiKey 只进 header);conformance runner 对 `requestBody*`/`requestHeaders` 比对,`schemaAccept` 调 `classifySchemaVersion`。
- **Java**:`JsonFixtureRequestSideContractTest` 用真实 wire DTO(`ReportRequest`/`RenewRequest`/`RegisterRequest`)序列化做出向 body 红线断言(report 字段名、partitionInvocationId);静态结构校验仍在 `JsonFixtureContractTest`。
- **Python**(2026-06-16 **转硬**):请求侧不再 `skip`。`test_contract_runner.py` 用 fixture 的 `given.state.request` 复刻出向请求,经**真实** `PlatformHttpClient` 打到 `pytest_httpx`,捕获实际出向 body+headers 断言 `requestBody*`/`requestHeaders`;`schemaAccept` 复用 SDK 的 `SCHEMA_VERSIONS_SUPPORTED`;响应侧分类(§B/§C)断言 typed exception + 累计 4xx fail-fast 阈值。idempotency-key mint 直接复用 SDK 的 `_new_idempotency_key()`(`sdk-py-<uuid4>`)。

> `given.state.request` 描述出向调用:`{kind: register|claim|renew|report, taskId?, partitionInvocationId?, idempotencyKey?, report?:{success,outputs,errorCode,resultSummary,failureClass}}`。partitionInvocationId 贯穿用 state 携带 claim 阶段存的 inv-id,renew/report 回填;不带则 body 省略(反例锁"该带没带 / 不该带乱带")。

### 2.1.1 增量 2/3(2026-06-16):动态背压 + §C 豁免 + 前向兼容

在 §2.1 基础上补 7 条 fixture(23–29),新增 1 个 optional 字段:

| 字段 | 类型 | runner 必须断言 |
|---|---|---|
| `effectiveMaxConcurrent` | int | 心跳 `desiredMaxConcurrent>0` 时 SDK 把有效本地并发上限收敛到该值(§2.1 动态背压);`null`/缺省则省略(沿用本地 config) |

覆盖项(fixture → 维度):

- **23** claim 非 401/409 的 4xx 累计第 5 次 → `fail-fast`(防活锁,与 21 首次不 fail-fast 成对)。
- **24** report 未给 idempotencyKey → SDK **自 mint** `<前缀>-<uuid4>`;正则 `^[a-z-]+[0-9a-f]{8}-[0-9a-f-]{8,}$` 锁"非固定值"(固定 `report-{taskId}` 凑不出 hex8 → 拒);前缀类放宽到 `[a-z-]`,兼容 Python 的多段 `sdk-py-` 与 go-/ts-/rs-/sdk- 单段(与 20 显式键透传互补)。
- **25** heartbeat 503 → §C 豁免:单次失败不退避(`retry:false`/`maxAttempts:1`),跳过本 tick。
- **26** 心跳 `platformStatus:DEGRADED` → `fsmTransition:DEGRADED` 且不 pause Kafka(`kafka:none`)。
- **27** 心跳 `desiredMaxConcurrent:2` → `effectiveMaxConcurrent:2`。
- **28** 收到 `workerType ∈ pausedTaskTypes` 的 Kafka 消息 → `kafka:drop-message`(不 commit offset,平台 unpause 后重投)。
- **29** 已知 major(v1)消息带未知前向字段 → `schemaAccept:true`(`ignoreUnknown`,不崩不拒;与 18 未知 major v3 reject 正交)。
- **30** 不可解码的 poison 记录(非 JSON / 字段非法)→ `kafka:commit-skip`(跳过**并提交** offset,避免一条损坏消息永久 HOL 阻塞分区;区别于 28 的 `drop-message` 不提交)。

**异构 runner 适配**:TS/Go/Rust 决策核新增 `classifyHeartbeatRenewError`(§C 豁免)+ `decidePausedTaskType`(paused drop),`applyHeartbeatDirective` 补 `DEGRADED` 分支与 `effectiveMaxConcurrent`,`classifyHttp` 已带累计 4xx 阈值(23)。Java `JsonFixtureContractTest` 静态校验全部 30 条(verb + OpenAPI path);请求侧 body 红线仍只覆盖带 `requestBody*` 的 fixture。Python 见下表。

**Python 后续清单(增量 2/3)**:

- [x] 23/24/26/27/29 + 21/22:转硬,真实 `PlatformHttpClient` / 分类断言全部通过。
- [ ] **25-heartbeat-503-no-backoff**:Python `heartbeat` 复用通用 `with_retry`,对所有 5xx 做指数退避,缺 §C 单次豁免分支。补 per-端点 no-backoff 是独立 production retry 行为变更,超出本增量范围 → 暂 `xfail(strict)`,标后续。
- [ ] **28-kafka-paused-task-type-drop**:Python `dispatcher.apply_platform_directive` 当前只落 `runtimeState`,未在 `on_message` 按 `pausedTaskTypes` 做 per-message drop(directive 已能解析 paused 集合,但不据此丢消息)→ 暂 `xfail(strict)`,补 drop 后驱动 `on_message` 断言不 claim 即转硬。

> 这两条 Python 后续项均为 strict `xfail`(真实违约才 xfail,实现后会 XPASS 报警提醒转硬),不是静默 skip;TS/Go/Rust/Java 对这两条均已硬断言,parity 不红。

## 3. 错误码 / 退避 / schemaVersion 的等价规则

机器字段之外,以下三类语义规则各语言必须**等价实现**(权威见 `wire-protocol.md`):

- **§B 错误码分类**:200 成功 / 401·403 fail-fast 不重试 / 404 放弃 / 409 幂等成功 / 其他 4xx 累计 5 次 fail-fast / 5xx·传输错指数退避。
- **§C 退避**:`base=200ms`,`maxAttempts=3`,指数 `base*2^(n-1)` = 200/400/800;register/claim/report 必走完整重试,heartbeat/renew 单次失败等下一 tick。
- **§A schemaVersion**:缺字段当 `v1`;已知 major(v1/v2)正常;未知 major(v3+)**reject 消息不 commit offset**;所有 DTO 等价 `ignoreUnknown`(平台加新字段旧 SDK 不崩)。

## 4. 落地清单(每新增一种语言)

- [ ] SDK 目录 `sdk/<lang>/`(core 保持平台运行时无关)
- [ ] 常量 codegen / loader 从 `sdk-shared-constants.yaml` 取值 + parity 测试
- [ ] contract runner 跑全部 fixtures 的 `then.expect`,100% 覆盖
- [ ] `sdk-contract-parity.yml`:加 `paths` + `<lang>-contract` job + 接入 parity-report
- [ ] `sdk-live-transport`:接入真实 Kafka/HTTP fake 接通测试;不能只靠 fixture/mock
- [ ] BYO guide §4 补该语言的"已知问题"

## 5. 引用

- 协议:[`wire-protocol.md`](wire-protocol.md) §A/§B/§C · [`byo-sdk-guide.md`](byo-sdk-guide.md)
- 常量源:[`docs/api/sdk-shared-constants.yaml`](../api/sdk-shared-constants.yaml)
- fixtures:[`docs/api/sdk-contract-fixtures/`](../api/sdk-contract-fixtures/) + `fixture-schema.json`(`then.expect` 闭集)
- CI:[`.github/workflows/sdk-contract-parity.yml`](../../.github/workflows/sdk-contract-parity.yml)
- ADR-035 §3/§4/§11
