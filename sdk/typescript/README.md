# @batch/worker-sdk (TypeScript BYO worker SDK)

A tenant-facing Bring-Your-Own worker SDK for the file-batch-system platform, built as a **decision core**: a set of pure functions in `src/decide.ts` that map wire-protocol inputs (HTTP status codes, heartbeat directives, lease-renew responses, capacity and stop signals) to structured results whose fields match the closed `then.expect` vocabulary of the platform's anti-drift contract (`docs/sdk/byo-conformance-contract.md` §2, `docs/sdk/wire-protocol.md` §A/§B/§C). Constants are consumed from `docs/api/sdk-shared-constants.yaml` (never re-authored) and kept honest by a parity test; behavior is proven against the contract fixtures by a conformance runner. Real HTTP/Kafka IO wraps these pure functions in a later phase — phase 1 is the decision core plus its conformance proof. Zero runtime dependencies; tests use Node's built-in runner with native TypeScript type-stripping.

**最低环境要求(消费方)**:**Node 22**(`engines.node`)。CI 覆盖 Node 22 和 24；发布产物是编译后的 ES2023 `dist/*.js`(非原始 `.ts`),运行时零依赖;Kafka 适配器用可选 `kafkajs`(`optionalDependencies`)。开发默认使用 Node 24，测试依赖 Node 原生 type-stripping。

> **⚠️ 包名 `@batch/worker-sdk` 仍是占位 scope,尚未在 npm 注册,现在 `npm install @batch/worker-sdk` 装不了。** 发布前会改成真实 scope(计划形如 `@yourorg/batch-worker-sdk`,由仓库管理员建 npm org 后定,见 [`docs/sdk/RELEASING.md`](../../docs/sdk/RELEASING.md))。在此之前请用 workspace 引用(monorepo 内 `"@batch/worker-sdk": "workspace:*"`),**不要**深相对路径 import 源码。下文示例里的 `@batch/worker-sdk` 会随真实 scope 敲定同步更新。

## Kafka 消费器(可选)

真实 kafkajs 消费适配器是独立子路径导出,核心 `import ... from "@batch/worker-sdk"` 不会拉入 kafkajs:

```ts
import { KafkaConsumerAdapter } from "@batch/worker-sdk/kafka";
```

装了可选依赖 `kafkajs` 才可用。适配器把每条记录交给 `MessagePipeline` 并手动提交 offset：accepted→commit；rejected/foreign-tenant→暂停当前分区并等待后重读；backpressure→回退并暂停，待容量恢复后继续；poison→commit-skip。已有消费组继续使用 broker 已提交位置；新消费组或提交位置失效时默认从最早保留位置读取，可能重读保留期内历史消息。显式配置 `fromBeginning: false` 可选最新位置，但首次安全位置保存失败后发生分区转交时可能跳过未处理消息。

## Running the tests

From this directory:

```bash
npm test
```

or directly:

```bash
node --test --experimental-strip-types 'tests/**/*.test.ts'
```

This runs:
- **`tests/contract.test.ts`** — loads the `docs/api/sdk-contract-fixtures/[0-9]*.json` fixtures, routes each to the right decision function from the shape of its `when` block (never from `then.expect`), and asserts every field present in `then.expect` deep-equals the computed result. Also asserts all fixtures are covered.
- **`tests/shared-constants-parity.test.ts`** — parses `docs/api/sdk-shared-constants.yaml` with a tiny hand-rolled list parser (no YAML dependency) and asserts each constant array in `src/constants.ts` deep-equals the YAML source of truth (§1.1 drift guard).

## Layout

- `src/constants.ts` — `SUPPORTED_SCHEMA_VERSIONS`, `WORKER_RUNTIME_STATES`, `SENSITIVE_KEYWORDS`, `TASK_STATUSES` (mirror of the YAML).
- `src/protocol.ts` — wire types: `HeartbeatResponse`, `RenewResponse`, `PlatformStatus`, error-code / failure-class enums.
- `src/decide.ts` — the pure decision functions: `classifyHttp`, `classifySchemaVersion`, `applyHeartbeatDirective`, `applyRenew`, `decideBackpressure`, `planStop`, `decideRegister`, plus `exponentialBackoff` / `parseIso8601DurationMs` helpers.
- `src/index.ts` — re-exports.

## P1 retry / idempotency

The runtime stays thin: retry and idempotency are explicit handler decorators, not
business templates and not framework wiring.

```ts
import {
  InMemoryIdempotencyStore,
  withIdempotency,
  withRetry,
} from "@batch/worker-sdk";

const handler = withRetry(
  withIdempotency(myHandler, new InMemoryIdempotencyStore()),
  { maxAttempts: 3 },
);
```

Production idempotency stores implement `SdkIdempotencyStore`; the SDK ships only
`NoopIdempotencyStore` and `InMemoryIdempotencyStore`.

## 实际执行入口计时

默认 `WorkerLifecycle` 在实际进入业务处理器前，通过原续租端点提交 `details.executionStarted=true` 和本次独立的 `executionAttemptId`，使用 CLAIM 返回的当前 invocation。同一次入口请求的传输重试复用该标识；确认失败或租约失效时不调用业务、不伪造业务失败回报；明确取消则保留取消结果。计时信号与非幂等副作用开始标记独立，处理器参数和业务批处理逻辑保持原契约。旧客户端和旧服务混版的保护边界见 [通讯协议](../../docs/sdk/wire-protocol.md#实际执行入口计时)。

消息入口等待 CLAIM 结果再决定 offset；尚在认领或执行的同任务重复投递返回 `withhold`，不会覆盖原执行的续租和取消登记。适配器暂停当前分区并回退到未确认位置，等待 2 秒后重读；其他分区继续消费。新消费组默认从最早保留位置读取，可能重读历史消息；新分配的分区在进入消息处理前保存当前读取位置，该位置不确认当前消息。显式配置 `fromBeginning:false` 可继续选择最新位置，但首次位置保存失败且发生转交时可能漏读。消费组重新分配时，适配器清除旧分配的提交屏障，新分配从 broker 的提交位置恢复。停机开始后迟到的执行开始确认不会启动新的业务处理，已进入的处理仍按停机窗口排空。消息可能重投，业务处理仍需遵循幂等契约。

等待认领结果期间，适配器维持 Kafka 消费组心跳，认领确认前不提交消息位点。停机开始时，尚未完成认领的消息按未确认处理并释放消费回调；迟到结果不会启动业务。关闭消息代理连接和通知控制面的耗时仍取决于外部依赖。
