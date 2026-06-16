# BYO SDK Conformance Contract（防漂移契约)

> **目的**:把"一个 BYO(Bring-Your-Own)SDK 算不算合规"从口头约定变成**机器可断言的硬契约**。
> 任何语言实现(Java / Python / **Go / TypeScript / Rust** / .NET …)要被认作平台兼容,必须满足本文 §1 的三条**强制约束**。
> 这是 ADR-035 Lane P(drift guard)的**单一权威源**,新增语言前先读本文。

## 0. 为什么需要这份契约

`wire-protocol.md` 是人读权威,`then.sdkExpectedAction` 是中文 prose——**多语言各自解读 prose = 行为天然漂移**。
本契约用两个机器化锚点消除这个洞:

1. **常量**锚在 [`docs/api/sdk-shared-constants.yaml`](../api/sdk-shared-constants.yaml)(各语言 **consume,禁 re-author**)。
2. **行为**锚在 [`docs/api/sdk-contract-fixtures/`](../api/sdk-contract-fixtures/) 每个 fixture 的 `then.expect`**结构化字段**(各语言断言同一组离散字段,不解读 prose)。

## 1. 三条强制约束(不满足 = 不合规)

### 1.1 常量必须从 `sdk-shared-constants.yaml` 生成,禁手写

`schema_versions_supported` / `worker_runtime_states` / `sensitive_keywords` / `task_statuses` 等跨语言常量,
SDK 侧**只能 consume 该 YAML**(codegen 或运行时加载),**严禁在各语言源码里重新声明字面量**。
每语言必须有一个 parity 测试,断言其常量产物 == YAML(对照 Java `SharedConstantsParityTest`)。

> Authority order(见 YAML 头注释):Java enum → 该 YAML → 其他语言 consume。改值只能从 Java 起,YAML 跟,其他语言自动同步。

### 1.2 必须通过全部 contract fixtures 的 `then.expect`

每语言必须有一个 **contract runner**,加载 `sdk-contract-fixtures/*.json`,按 `given/when` 驱动 SDK,
断言 `then.expect` 的**每个出现的字段**(见 §2 字段语义)。`then.sdkExpectedAction` 仅作人读注释,**不作断言依据**。
覆盖必须 100%(当前 12 个场景,数量随协议演进增长)。

### 1.3 必须接入 `sdk-contract-parity.yml` CI

新语言 SDK 落地时,必须:
- 把其目录加进 `.github/workflows/sdk-contract-parity.yml` 的 `paths:` 触发器;
- 增加一个 `<lang>-contract` job 跑该语言的 runner + parity 测试;
- 接入 `parity-report` 的 N 语言 pass-set 对账。

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
| `kafka` | enum | consumer 动作:`none`/`subscribe`/`pause`/`resume`/`wakeup`/`drop-message` |
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

## 3. 错误码 / 退避 / schemaVersion 的等价规则

机器字段之外,以下三类语义规则各语言必须**等价实现**(权威见 `wire-protocol.md`):

- **§B 错误码分类**:200 成功 / 401·403 fail-fast 不重试 / 404 放弃 / 409 幂等成功 / 其他 4xx 累计 5 次 fail-fast / 5xx·传输错指数退避。
- **§C 退避**:`base=200ms`,`maxAttempts=3`,指数 `base*2^(n-1)` = 200/400/800;register/claim/report 必走完整重试,heartbeat/renew 单次失败等下一 tick。
- **§A schemaVersion**:缺字段当 `v1`;已知 major(v1/v2)正常;未知 major(v3+)**reject 消息不 commit offset**;所有 DTO 等价 `ignoreUnknown`(平台加新字段旧 SDK 不崩)。

## 4. 落地清单(每新增一种语言)

- [ ] SDK 目录 `batch-worker-sdk-<lang>/`(core 保持平台运行时无关)
- [ ] 常量 codegen / loader 从 `sdk-shared-constants.yaml` 取值 + parity 测试
- [ ] contract runner 跑全部 fixtures 的 `then.expect`,100% 覆盖
- [ ] `sdk-contract-parity.yml`:加 `paths` + `<lang>-contract` job + 接入 parity-report
- [ ] BYO guide §4 补该语言的"已知坑"

## 5. 引用

- 协议:[`wire-protocol.md`](wire-protocol.md) §A/§B/§C · [`byo-sdk-guide.md`](byo-sdk-guide.md)
- 常量源:[`docs/api/sdk-shared-constants.yaml`](../api/sdk-shared-constants.yaml)
- fixtures:[`docs/api/sdk-contract-fixtures/`](../api/sdk-contract-fixtures/) + `fixture-schema.json`(`then.expect` 闭集)
- CI:[`.github/workflows/sdk-contract-parity.yml`](../../.github/workflows/sdk-contract-parity.yml)
- ADR-035 §3/§4/§11
