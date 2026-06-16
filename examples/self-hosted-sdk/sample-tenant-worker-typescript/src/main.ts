/**
 * sample-tenant-worker-typescript — ADR-035 self-hosted tenant worker (TS BYO SDK).
 *
 * Minimal runnable worker wiring the TypeScript BYO SDK against the platform:
 *   HttpTransport (control plane) + KafkaConsumerAdapter (dispatch) + an example
 *   TaskHandler, driven by WorkerLifecycle with a SensitiveDataValidator and a
 *   graceful SIGTERM → stop(30000).
 *
 * Zero build step: run directly with `node --experimental-strip-types src/main.ts`
 * (npm start). It only CONNECTS when invoked as the entrypoint, so a load/type-strip
 * check (`node --check --experimental-strip-types src/main.ts`) stays offline-safe.
 *
 * The SDK is not published; it is imported by RELATIVE path from source
 * (examples/sample-tenant-worker-typescript/src → repo root is ../../../).
 */

import {
  HttpTransport,
  WorkerLifecycle,
  SensitiveDataValidator,
  ErrorCode,
  taskSuccess,
  consoleLogger,
  type TaskHandler,
  type TaskContext,
  type TaskResult,
} from "../../../sdk/typescript/src/index.ts";
import {
  KafkaConsumerAdapter,
  type KafkaConsumerConfig,
  type KafkaSaslConfig,
} from "../../../sdk/typescript/kafka/kafkaConsumer.ts";

const logger = consoleLogger;

/** Resolved configuration sourced ENTIRELY from env (credentials never from payload). */
interface WorkerEnv {
  baseUrl: string;
  apiKey: string;
  tenantId: string;
  workerCode: string;
  kafkaBootstrap: string[];
  ssl: boolean;
  sasl?: KafkaSaslConfig;
}

/** Read + validate config from env; fail fast listing ALL missing required vars. */
function loadEnv(): WorkerEnv {
  const required = {
    BATCH_BASE_URL: process.env.BATCH_BASE_URL,
    BATCH_API_KEY: process.env.BATCH_API_KEY,
    BATCH_TENANT_ID: process.env.BATCH_TENANT_ID,
    BATCH_WORKER_CODE: process.env.BATCH_WORKER_CODE,
    KAFKA_BOOTSTRAP: process.env.KAFKA_BOOTSTRAP,
  };
  const missing = Object.entries(required)
    .filter(([, v]) => !v || v.trim() === "")
    .map(([k]) => k);
  if (missing.length > 0) {
    throw new Error(
      `missing required env var(s): ${missing.join(", ")} ` +
        `(set BATCH_BASE_URL / BATCH_API_KEY / BATCH_TENANT_ID / BATCH_WORKER_CODE / KAFKA_BOOTSTRAP)`,
    );
  }

  // Optional SASL/SCRAM-SHA-512 — supply all three to enable; otherwise PLAINTEXT.
  const saslUser = process.env.KAFKA_SASL_USERNAME;
  const saslPass = process.env.KAFKA_SASL_PASSWORD;
  let sasl: KafkaSaslConfig | undefined;
  if (saslUser && saslPass) {
    sasl = { mechanism: "scram-sha-512", username: saslUser, password: saslPass };
  } else if (saslUser || saslPass) {
    throw new Error(
      "partial SASL config: set BOTH KAFKA_SASL_USERNAME and KAFKA_SASL_PASSWORD, or neither",
    );
  }

  return {
    baseUrl: required.BATCH_BASE_URL!,
    apiKey: required.BATCH_API_KEY!,
    tenantId: required.BATCH_TENANT_ID!,
    workerCode: required.BATCH_WORKER_CODE!,
    kafkaBootstrap: required
      .KAFKA_BOOTSTRAP!.split(",")
      .map((b) => b.trim())
      .filter((b) => b.length > 0),
    // TLS on the wire when SASL is present (SASL_SSL) or BATCH_KAFKA_SSL=true.
    ssl: process.env.BATCH_KAFKA_SSL === "true" || sasl !== undefined,
    sasl,
  };
}

/**
 * Example handler: logs the task and echoes its effective config back as outputs,
 * mirroring the python/java samples' `echo` handler. Returns a success TaskResult.
 */
class EchoHandler implements TaskHandler {
  async execute(ctx: TaskContext): Promise<TaskResult> {
    logger.info("echo handler executing", {
      taskId: ctx.taskId,
      traceId: ctx.traceId,
      configKeys: Object.keys(ctx.effectiveConfig),
    });
    if (ctx.cancellation.isCancellationRequested) {
      return { success: false, errorCode: ErrorCode.CANCELLED, resultSummary: "cancelled before run" };
    }
    return taskSuccess(
      { echo: ctx.effectiveConfig },
      `echoed taskId=${ctx.taskId}`,
    );
  }
}

async function main(): Promise<void> {
  const env = loadEnv();
  logger.info("starting sample-tenant-worker-typescript", {
    baseUrl: env.baseUrl,
    tenantId: env.tenantId,
    workerCode: env.workerCode,
    brokers: env.kafkaBootstrap,
    sasl: env.sasl ? "scram-sha-512" : "plaintext",
  });

  // 1. control-plane transport (apiKey via Authorization header).
  const transport = new HttpTransport({
    baseUrl: env.baseUrl,
    headers: { authorization: `Bearer ${env.apiKey}` },
  });

  // 2. dispatch consumer (real kafkajs adapter; PLAINTEXT or SASL from env).
  const kafkaConfig: KafkaConsumerConfig = {
    brokers: env.kafkaBootstrap,
    tenantId: env.tenantId,
    workerCode: env.workerCode,
    ssl: env.ssl,
    sasl: env.sasl,
  };
  const consumer = new KafkaConsumerAdapter(kafkaConfig, logger);

  // 3. lifecycle: register → schedulers → subscribe; validator guards credential leaks.
  const lifecycle = new WorkerLifecycle({
    config: { tenantId: env.tenantId, workerCode: env.workerCode, maxConcurrent: 4 },
    transport,
    consumer,
    handler: new EchoHandler(),
    validator: new SensitiveDataValidator(),
    logger,
  });

  // 4. graceful shutdown — SIGTERM is wired by the lifecycle; add SIGINT (Ctrl-C) too.
  const onSigint = () => {
    logger.info("SIGINT received; graceful stop(30000)");
    void lifecycle.stop(30_000).finally(() => {
      transport.close();
      process.exit(0);
    });
  };
  process.once("SIGINT", onSigint);

  await lifecycle.start();
  logger.info("worker started; awaiting dispatch", { fsm: lifecycle.fsm });
}

// Only connect when run as the entrypoint — keeps `--check` / import-graph load offline-safe.
if (import.meta.main) {
  // §4 Node pit: a stray rejection must not hang SIGTERM. The lifecycle installs a
  // process-wide guard too, but arm one here before start() in case wiring throws.
  process.on("unhandledRejection", (reason) => {
    logger.error("unhandledRejection", { reason: String(reason) });
  });
  main().catch((err) => {
    logger.error("fatal: worker failed to start", { error: String(err) });
    process.exit(1);
  });
}
