# SDK Contract Fixtures(language-agnostic)

> **用途**:为 BYO(Bring Your Own)SDK 提供**与语言无关**的协议行为对账用例。
> 任何语言实现(Go / Python / Node / .NET / Rust …)都可写一个 contract runner,按 fixture JSON 的
> `given` 起 SDK、`when` 触发 HTTP / Kafka 调用、断言 `then.sdkExpectedAction` 描述的行为。
>
> **权威源**:本目录与 [`docs/sdk/wire-protocol.md`](../../sdk/wire-protocol.md) §B/§C 错误码 + 重试规则、
> [`docs/api/orchestrator-internal.openapi.yaml`](../orchestrator-internal.openapi.yaml) schema 三轨对齐。

## Fixture 结构

```json
{
  "scenario": "<scenario-name-kebab>",
  "description": "...",
  "given": {
    "config": { /* SDK 启动配置(tenantId / workerCode / apiKey / ...) */ },
    "state":  { /* 可选:模拟当前 SDK 状态(in-flight tasks / FSM state) */ }
  },
  "when": {
    "channel": "http" | "kafka",
    "method":  "POST" | "GET" | "RECEIVE",
    "path":    "/internal/...",
    "body":    { /* SDK 发出 / 收到的 payload */ },
    "responseStatus": 200,
    "responseBody":   { /* 平台回包 */ },
    "responseHeaders": { /* 可选 */ }
  },
  "then": {
    "sdkExpectedAction": "human-readable 必须发生的 SDK 行为(状态切换 / 调度器启停 / 重试 / fail-fast)",
    "sdkMustNot":        ["列出明确禁止的行为(如 重试 401 / 修改 errorCode 字段名)"]
  },
  "references": [ "wire-protocol.md §B", "openapi: /internal/.../register" ]
}
```

## 用例清单(本目录)

| 文件 | 覆盖 |
|---|---|
| `01-register-success.json` | register 200 → 状态切 ONLINE, 启动 Heartbeat / Lease scheduler |
| `02-register-conflict-idempotent.json` | register 200 with existing workerCode → 平台 idempotent,SDK 同样进 ONLINE |
| `03-heartbeat-directive-normal.json` | heartbeat 200, platformStatus=NORMAL → 维持当前 FSM |
| `04-heartbeat-directive-draining.json` | heartbeat 200, shouldDrain=true → 进 DRAINING, Kafka pause |
| `05-heartbeat-directive-paused.json` | heartbeat 200, platformStatus=PAUSED → Kafka assignment.pause(),不收新 task |
| `06-heartbeat-next-interval-hint.json` | heartbeat 200, nextHeartbeatHint=PT15S → 调度器下次 15s 后跑 |
| `07-claim-401-fail-fast.json` | claim 401 → fail-fast, dispatcher.fatal, **不重试** |
| `08-claim-409-idempotent-success.json` | claim 409 → 视为成功, log INFO, 不报告失败 |
| `09-report-5xx-retry-backoff.json` | report 503 三次 → 指数退避 200/400/800ms, 仍失败则 log + drop |
| `10-renew-cancel-requested.json` | renew 200, cancelRequested=true → 触发 CancellationSignal, handler 中止 |
| `11-kafka-partition-pause-on-capacity.json` | in-flight 满 → Kafka assignment.pause(partitions), 处理完 resume |
| `12-stop-with-timeout.json` | stop(30s) → draining=true, Kafka wakeup, drain in-flight, deactivate, 不超 30s |
| `13-report-field-names-redline.json` | **请求侧**:report 出向 body 必含 `success:bool`/`taskId`/`tenantId`/`workerId`/`outputs`,必不含废名 `output`/`errorClass`/`errorMessage`/`status` |
| `14-partition-invocation-id-passthrough.json` | **请求侧**:claim 存的 `partitionInvocationId=inv-1` → renew 出向 body 回填同一 id |
| `15-partition-invocation-id-absent-when-unclaimed.json` | **请求侧**(反例):claim 没存 inv-id → renew body 不捏造 partitionInvocationId |
| `16-kafka-schema-version-missing-accept.json` | schemaVersion 缺失 → 当 v1,accept |
| `17-kafka-schema-version-v2-accept.json` | schemaVersion=v2(已知 major)→ accept |
| `18-kafka-schema-version-v3-reject.json` | schemaVersion=v3(未知 major)→ reject,不 commit offset |
| `19-register-apikey-in-header-not-body.json` | **请求侧**:apiKey 走 `X-Batch-Api-Key` header,不进 register body |
| `20-report-idempotency-key-header.json` | **请求侧**:report 带 `Idempotency-Key` header(`<lang>-<uuid>` 形态) |
| `21-claim-4xx-client-error-no-failfast.json` | 单次非 401/409 的 4xx → client-error,不重试不 fail-fast(累计阈值 5) |
| `22-renew-404-not-found-give-up.json` | renew 404 → not-found,放弃不重试不 fail-fast |

> **请求侧** fixture(13/14/15/19/20)用 `then.expect.requestBodyIncludes`/`requestBodyExcludes`/`requestHeaders` 断言 SDK **出向**请求;`schemaAccept`(16-18)断言 kafka 消息按 schemaVersion accept/reject。字段语义见 `docs/sdk/byo-conformance-contract.md` §2.1。

## 实现这套 runner 的建议

- **HTTP 侧**:用 mock server(Go 用 `httptest`、Python 用 `responses`、Node 用 `nock`)按 fixture `when.responseStatus` 回包
- **Kafka 侧**:用 in-memory / embedded broker 或 mock consumer
- **断言**:`then.sdkExpectedAction` 是人类可读描述,需要 runner 端把行为翻译为可观察事件(状态变量、调度器是否启停、HTTP 重试次数)
- **CI**:本 lane 暂不强制平台 CI 跑这套(各语言 runner 维护成本平台不承担);租户上线评审用 / BYO SDK 自家 CI 用

## Fixture 写法纪律（Lane P drift guard）

- 每个 `*.json` **必须**通过 [`fixture-schema.json`](./fixture-schema.json)（JSON Schema draft 2020-12）校验
- 本地验证：`bash docs/api/sdk-contract-fixtures/validate.sh`（需 `pip install jsonschema`）
- CI：`.github/workflows/sdk-contract-parity.yml` 的 `validate-fixtures` job 强制执行，任一 schema violation 阻断 PR
- 新 fixture 加入时，同步对齐 `orchestrator-internal.openapi.yaml`（path + method）与 `wire-protocol.md`（语义），三轨不一致 = Java `JsonFixtureContractTest` 直接失败

## 协议演进

平台改 wire schema 时:
1. 改 `WorkerController` / `TaskController` Java 代码
2. 改 [`orchestrator-internal.openapi.yaml`](../orchestrator-internal.openapi.yaml)
3. 改 [`wire-protocol.md`](../../sdk/wire-protocol.md) Changelog
4. **改本目录对应 fixture**(或加新 fixture),BYO SDK 团队订阅本目录变更
