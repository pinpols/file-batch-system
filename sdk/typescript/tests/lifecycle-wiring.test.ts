/**
 * WorkerLifecycle production wiring (the P0 review findings):
 *   - the consumer callback returns a MessageDisposition, so the real Kafka
 *     adapter can commit / withhold / backpressure (the old start() path ignored
 *     the outcome → offsets never advanced → every restart replayed the topic).
 *   - a claim 409 (idempotent-already-claimed) SKIPS handler + report.
 *   - partitionInvocationId is sourced from the dispatch message and sent in the
 *     claim body (parity with the four other SDKs), then echoed on report.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { WorkerLifecycle } from "../src/client/lifecycle.ts";
import { FakePlatform } from "../src/client/testkit.ts";
import {
  type Consumer,
  type ConsumerRecord,
  type MessageDisposition,
} from "../src/client/consumer.ts";
import { taskSuccess, type TaskHandler } from "../src/client/handler.ts";

const silentLogger = { info: () => {}, warn: () => {}, error: () => {} };
const baseConfig = { tenantId: "tenant-A", workerCode: "w1", maxConcurrent: 4 };

/** Consumer that captures the disposition returned for each delivered record. */
class CapturingConsumer implements Consumer {
  readonly dispositions: MessageDisposition[] = [];
  #records: ConsumerRecord[];
  #paused = false;
  constructor(records: ConsumerRecord[]) {
    this.#records = records;
  }
  async start(
    onMessage: (r: ConsumerRecord) => Promise<MessageDisposition>,
  ): Promise<void> {
    for (const r of this.#records) {
      this.dispositions.push(await onMessage(r));
    }
  }
  async wakeup(): Promise<void> {}
  pause(): void {
    this.#paused = true;
  }
  resume(): void {
    this.#paused = false;
  }
  isPaused(): boolean {
    return this.#paused;
  }
}

function rec(msg: unknown): ConsumerRecord {
  return { value: JSON.stringify(msg) };
}

test("lifecycle: production callback returns 'commit' for an accepted message", async () => {
  const platform = new FakePlatform({ claim: { effectiveConfig: {} } });
  const consumer = new CapturingConsumer([
    rec({ taskId: "t1", tenantId: "tenant-A", workerType: "IMPORT", schemaVersion: "v1" }),
  ]);
  const lc = new WorkerLifecycle({
    config: baseConfig,
    transport: platform.transport,
    consumer,
    handler: { execute: async () => taskSuccess() },
    logger: silentLogger,
    installSignalHandlers: false,
  });
  await lc.start();
  await new Promise((r) => setTimeout(r, 10));
  assert.deepEqual(consumer.dispositions, ["commit"]);
  await lc.stop(100);
});

test("lifecycle: production callback returns 'withhold' for a foreign-tenant message (never commit, §1.9)", async () => {
  const platform = new FakePlatform({ claim: { effectiveConfig: {} } });
  const consumer = new CapturingConsumer([
    rec({ taskId: "t1", tenantId: "OTHER", workerType: "IMPORT" }),
  ]);
  const lc = new WorkerLifecycle({
    config: baseConfig,
    transport: platform.transport,
    consumer,
    handler: { execute: async () => taskSuccess() },
    logger: silentLogger,
    installSignalHandlers: false,
  });
  await lc.start();
  await new Promise((r) => setTimeout(r, 10));
  assert.deepEqual(consumer.dispositions, ["withhold"]);
  assert.equal(platform.transport.countOf("claim"), 0, "foreign tenant never claimed");
  await lc.stop(100);
});

test("lifecycle: at capacity, the second message yields 'backpressure' (seek+pause, not commit)", async () => {
  const platform = new FakePlatform({ claim: { effectiveConfig: {} } });
  // handler holds t1 in-flight (fills capacity) via a controllable gate; released
  // after the assertion so nothing leaks a forever-pending promise into node:test's
  // event loop (which would flag this and every later async test in the file).
  let releaseT1!: () => void;
  const t1Gate = new Promise<void>((r) => {
    releaseT1 = r;
  });
  const handler: TaskHandler = { execute: () => t1Gate.then(() => taskSuccess()) };
  const consumer = new CapturingConsumer([
    rec({ taskId: "t1", tenantId: "tenant-A", workerType: "IMPORT" }),
    rec({ taskId: "t2", tenantId: "tenant-A", workerType: "IMPORT" }),
  ]);
  const lc = new WorkerLifecycle({
    config: { ...baseConfig, maxConcurrent: 1 },
    transport: platform.transport,
    consumer,
    handler,
    logger: silentLogger,
    installSignalHandlers: false,
  });
  await lc.start();
  await new Promise((r) => setTimeout(r, 10));
  assert.deepEqual(consumer.dispositions, ["commit", "backpressure"]);
  releaseT1();
  await lc.stop(100);
});

test("lifecycle: claim 409 (idempotent) SKIPS handler execution and report", async () => {
  const platform = new FakePlatform({ claim: { idempotent: true } });
  platform.feedMessages({ taskId: "t-dup", tenantId: "tenant-A", workerType: "IMPORT" });
  let handlerRan = false;
  const lc = new WorkerLifecycle({
    config: baseConfig,
    transport: platform.transport,
    consumer: platform.consumer,
    handler: {
      execute: async () => {
        handlerRan = true;
        return taskSuccess();
      },
    },
    logger: silentLogger,
    installSignalHandlers: false,
  });
  await lc.start();
  await new Promise((r) => setTimeout(r, 10));
  assert.equal(platform.transport.countOf("claim"), 1, "claim was attempted");
  assert.equal(handlerRan, false, "handler must NOT run on an already-claimed task");
  assert.equal(platform.transport.countOf("report"), 0, "no report for a skipped task");
  await lc.stop(100);
});

test("lifecycle: partitionInvocationId is read from the dispatch message and sent in the claim body", async () => {
  const platform = new FakePlatform({ claim: { effectiveConfig: {} } });
  platform.feedMessages({
    taskId: "t-inv",
    tenantId: "tenant-A",
    workerType: "IMPORT",
    runtimeAttributes: { partitionInvocationId: "inv-9", traceId: "tr-1" },
  });
  const lc = new WorkerLifecycle({
    config: baseConfig,
    transport: platform.transport,
    consumer: platform.consumer,
    handler: { execute: async () => taskSuccess() },
    logger: silentLogger,
    installSignalHandlers: false,
  });
  await lc.start();
  await new Promise((r) => setTimeout(r, 10));

  // FakeTransport.claim records (taskId, idempotencyKey, partitionInvocationId).
  const claim = platform.transport.calls.find((c) => c.op === "claim")!;
  assert.equal(claim.args[2], "inv-9", "claim body carries the message's partitionInvocationId");

  const report = platform.transport.calls.find((c) => c.op === "report")!;
  const body = report.args[1] as Record<string, unknown>;
  assert.equal(body.partitionInvocationId, "inv-9", "report echoes the same invocation id");
  await lc.stop(100);
});
