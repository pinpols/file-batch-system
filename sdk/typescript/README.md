# @batch/worker-sdk (TypeScript BYO worker SDK)

A tenant-facing Bring-Your-Own worker SDK for the file-batch-system platform, built as a **decision core**: a set of pure functions in `src/decide.ts` that map wire-protocol inputs (HTTP status codes, heartbeat directives, lease-renew responses, capacity and stop signals) to structured results whose fields match the closed `then.expect` vocabulary of the platform's anti-drift contract (`docs/sdk/byo-conformance-contract.md` §2, `docs/sdk/wire-protocol.md` §A/§B/§C). Constants are consumed from `docs/api/sdk-shared-constants.yaml` (never re-authored) and kept honest by a parity test; behavior is proven against the contract fixtures by a conformance runner. Real HTTP/Kafka IO wraps these pure functions in a later phase — phase 1 is the decision core plus its conformance proof. Zero runtime dependencies; tests use Node's built-in runner with native TypeScript type-stripping.

**最低环境要求(消费方)**:**Node 22**(`engines.node`)—— 发布产物是编译后的 ES2023 `dist/*.js`(非原始 `.ts`),运行时零依赖;Kafka 适配器用可选 `kafkajs`(`optionalDependencies`)。开发/跑测试使用 Node 22 的原生 type-stripping 能力。

> **⚠️ 包名 `@batch/worker-sdk` 仍是占位 scope,尚未在 npm 注册,现在 `npm install @batch/worker-sdk` 装不了。** 发布前会改成真实 scope(计划形如 `@yourorg/batch-worker-sdk`,由仓库管理员建 npm org 后定,见 [`docs/sdk/RELEASING.md`](../../docs/sdk/RELEASING.md))。在此之前请用 workspace 引用(monorepo 内 `"@batch/worker-sdk": "workspace:*"`),**不要**深相对路径 import 源码。下文示例里的 `@batch/worker-sdk` 会随真实 scope 敲定同步更新。

## Kafka 消费器(可选)

真实 kafkajs 消费适配器是独立子路径导出,核心 `import ... from "@batch/worker-sdk"` 不会拉入 kafkajs:

```ts
import { KafkaConsumerAdapter } from "@batch/worker-sdk/kafka";
```

装了可选依赖 `kafkajs` 才可用。适配器把每条记录喂给 `MessagePipeline` 并执行手动 offset 策略:accepted→commit;rejected/foreign-tenant→记录分区 commit ceiling 后继续消费(后续 commit 不能跨过该 offset,避免 HOL 阻塞);backpressure→seek 回退 + 临时 pause;poison→commit-skip。消费起点默认 `latest`(`fromBeginning` 默认 `false`,与其余四语言 SDK 对齐),显式传 `fromBeginning: true` 才从头消费。

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
