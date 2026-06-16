# sample-tenant-worker-typescript — ADR-035 self-hosted worker (TypeScript BYO SDK)

Minimal runnable self-hosted tenant worker built on the **TypeScript BYO SDK**
(`sdk/typescript`). It wires the SDK's `HttpTransport` (control plane), the real
`KafkaConsumerAdapter` (dispatch, kafkajs), an example `TaskHandler`, and the
`WorkerLifecycle` FSM with a `SensitiveDataValidator` and graceful SIGTERM.

> **Same self-hosted capability, multiple stacks** — pick by tenant tech:
> - [`../sample-tenant-worker`](../sample-tenant-worker/) — Java + hand-written `main`
> - [`../sample-tenant-worker-spring`](../sample-tenant-worker-spring/) — Java + Spring Boot starter
> - [`../sample-tenant-worker-python`](../sample-tenant-worker-python/) — Python 3.12+ + asyncio
> - **`sample-tenant-worker-typescript` (this dir)** — Node + native TS type-stripping
>
> Other languages → [BYO SDK guide](../../docs/sdk/byo-sdk-guide.md).

## What it does

1. Reads config from env, failing fast and listing **all** missing required vars.
2. Builds `HttpTransport` with the API key as a `Bearer` Authorization header.
3. Builds `KafkaConsumerAdapter` (PLAINTEXT, or SASL/SCRAM-SHA-512 if SASL env is set).
4. Registers an `EchoHandler` that logs the task and echoes its effective config
   back as a success `TaskResult` (mirrors the python/java `echo` handler).
5. Drives it all with `WorkerLifecycle` (`start()`), plus SIGTERM (wired by the
   lifecycle) and SIGINT → `stop(30000)`.
6. Installs a process-wide `unhandledRejection` guard (SDK §4 Node pit).

The SDK is **not published**; it is imported by relative path from source:

| import | path (from `src/main.ts`) |
| --- | --- |
| public SDK surface | `../../../sdk/typescript/src/index.ts` |
| kafkajs adapter | `../../../sdk/typescript/kafka/kafkaConsumer.ts` |

`../../../` climbs out of `src/` → example dir → `examples/` to the repo root.

## Env vars

| Var | Required | Meaning |
| --- | --- | --- |
| `BATCH_BASE_URL` | ✅ | Platform control-plane base URL, e.g. `https://batch.example.com` |
| `BATCH_API_KEY` | ✅ | API key; sent as `Authorization: Bearer <key>` |
| `BATCH_TENANT_ID` | ✅ | Tenant this worker serves (topic regex + self-check) |
| `BATCH_WORKER_CODE` | ✅ | Worker code (part of the consumer group id) |
| `KAFKA_BOOTSTRAP` | ✅ | Comma-separated brokers, e.g. `kafka.example.com:9092` |
| `BATCH_KAFKA_SSL` | — | `true` to force TLS (auto-on when SASL is set) |
| `KAFKA_SASL_USERNAME` | — | SASL/SCRAM-SHA-512 username (set with password to enable SASL) |
| `KAFKA_SASL_PASSWORD` | — | SASL/SCRAM-SHA-512 password |

> **Credential discipline**: credentials come from env only — never from a
> dispatch payload or task parameters. The `SensitiveDataValidator` rejects
> credential-looking fields in the register body / task params.

## Run

```bash
npm install            # fetch kafkajs (the SDK's kafka adapter depends on it)

export BATCH_BASE_URL=https://batch.example.com
export BATCH_API_KEY=...
export BATCH_TENANT_ID=tenant-xyz
export BATCH_WORKER_CODE=xyz-sample-ts-1
export KAFKA_BOOTSTRAP=kafka.example.com:9092
# optional SASL:
# export KAFKA_SASL_USERNAME=...
# export KAFKA_SASL_PASSWORD=...

npm start              # node --experimental-strip-types src/main.ts
```

`npm start` needs a **live platform + broker** to actually register and consume.

## Verify offline (no platform/broker)

`main.ts` only connects when run as the entrypoint (`import.meta.main`), so the
type-strip / import-graph load is offline-safe:

```bash
npm run check          # node --check --experimental-strip-types src/main.ts
```

This resolves the SDK relative imports + kafkajs and type-strips with no syntax
errors. It does **not** start the worker.

## Make it yours

Copy this directory into your repo and:

1. Change `package.json` `name`.
2. Replace the env wiring in `src/main.ts` with your config source (Vault / K8s Secret).
3. Replace `EchoHandler` with your business handler (implement `TaskHandler`).

Do **not** pull in a web framework — the SDK is deliberately minimal so it does
not drag heavy deps into the tenant process.
