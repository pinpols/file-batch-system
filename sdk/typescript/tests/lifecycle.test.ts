/**
 * WorkerLifecycle tests — start→stop ordering, drains in-flight, full
 * claim→execute→report flow, SIGTERM path. Uses FakePlatform (no broker/HTTP).
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { WorkerLifecycle } from "../src/client/lifecycle.ts";
import { FakePlatform, fatalClaimTransport } from "../src/client/testkit.ts";
import { FakeConsumer } from "../src/client/consumer.ts";
import {
  taskSuccess,
  type TaskContext,
  type TaskHandler,
} from "../src/client/handler.ts";

const silentLogger = { info: () => {}, warn: () => {}, error: () => {} };

const baseConfig = {
  tenantId: "tenant-A",
  workerCode: "w1",
  maxConcurrent: 4,
};

test("lifecycle: start registers, starts schedulers, then runs claim→execute→report", async () => {
  const platform = new FakePlatform(
    { claim: { effectiveConfig: { k: "v" }, traceId: "trace-1" } },
  );
  platform.feedMessages({ taskId: "task-1", tenantId: "tenant-A", schemaVersion: "v1" });

  let seenCtx: TaskContext | undefined;
  const handler: TaskHandler = {
    execute: async (ctx) => {
      seenCtx = ctx;
      return taskSuccess({ rows: 3 }, "ok");
    },
  };

  const lc = new WorkerLifecycle({
    config: baseConfig,
    transport: platform.transport,
    consumer: platform.consumer,
    handler,
    logger: silentLogger,
    installSignalHandlers: false,
  });

  await lc.start();
  // let the dispatched task settle
  await new Promise((r) => setTimeout(r, 10));

  const ops = platform.transport.calls.map((c) => c.op);
  assert.ok(ops.indexOf("register") === 0, "register first");
  assert.ok(ops.includes("claim"), "claim happened");
  assert.ok(ops.includes("report"), "report happened");

  // report body carries exact field names + claimed config in ctx
  const report = platform.transport.calls.find((c) => c.op === "report");
  const body = report!.args[1] as Record<string, unknown>;
  assert.equal(body.success, true);
  assert.deepEqual(body.outputs, { rows: 3 });
  assert.equal(body.resultSummary, "ok");
  assert.equal(seenCtx?.effectiveConfig.k, "v");
  assert.equal(seenCtx?.traceId, "trace-1");

  await lc.stop(200);
});

test("lifecycle: claim + report mint fresh ts-<uuid> keys (never fixed claim-/report-{id})", async () => {
  // fixture 24: a redelivered task's report must not replay a stale outcome.
  const platform = new FakePlatform({ claim: { effectiveConfig: {} } });
  platform.feedMessages({ taskId: "task-1", tenantId: "tenant-A", schemaVersion: "v1" });
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

  const claimKey = platform.transport.calls.find((c) => c.op === "claim")!
    .args[1] as string;
  const reportKey = platform.transport.calls.find((c) => c.op === "report")!
    .args[2] as string;
  for (const [name, key] of [["claim", claimKey], ["report", reportKey]] as const) {
    assert.ok(key.startsWith("ts-"), `${name} key must be minted ts-<uuid>, got ${key}`);
    assert.notEqual(key, `${name}-task-1`, `${name} must not use a fixed key`);
  }
  assert.notEqual(claimKey, reportKey, "claim and report must use distinct keys");

  await lc.stop(200);
});

test("lifecycle: threads partitionInvocationId from claim into report (+ default bodies carry required ids)", async () => {
  const platform = new FakePlatform(
    { claim: { effectiveConfig: {}, partitionInvocationId: "inv-42" } },
  );
  platform.feedMessages({ taskId: "task-pi", tenantId: "tenant-A", workerType: "IMPORT" });

  const handler: TaskHandler = { execute: async () => taskSuccess() };
  const lc = new WorkerLifecycle({
    config: baseConfig,
    transport: platform.transport,
    consumer: platform.consumer,
    handler,
    logger: silentLogger,
    installSignalHandlers: false,
  });

  await lc.start();
  await new Promise((r) => setTimeout(r, 10));

  const claim = platform.transport.calls.find((c) => c.op === "claim")!;
  // claim body (args[1] is idempotencyKey for FakeTransport.claim; body asserted via HttpTransport tests)
  const report = platform.transport.calls.find((c) => c.op === "report")!;
  const body = report.args[1] as Record<string, unknown>;
  assert.equal(body.partitionInvocationId, "inv-42", "report echoes the claim invocation id");
  assert.ok(claim, "claim happened");

  await lc.stop(200);

  // deactivate default body carries the OFFLINE WorkerHeartbeatDto via HttpTransport;
  // here FakeTransport.deactivate only records workerCode, so assert it ran.
  assert.ok(platform.transport.countOf("deactivate") >= 1);
});

test("lifecycle: 401 on claim fails fast (stops + deactivates)", async () => {
  const transport = fatalClaimTransport(401);
  const consumer = new FakeConsumer([
    { value: JSON.stringify({ taskId: "task-401", tenantId: "tenant-A", workerType: "IMPORT" }) },
  ]);
  const handler: TaskHandler = { execute: async () => taskSuccess() };

  const lc = new WorkerLifecycle({
    config: baseConfig,
    transport,
    consumer,
    handler,
    logger: silentLogger,
    installSignalHandlers: false,
  });

  await lc.start();
  // allow the task IIFE (claim → 401 → fail-fast stop) to run
  await new Promise((r) => setTimeout(r, 60));

  assert.equal(lc.fsm, "DRAINING", "auth failure drove a fail-fast stop");
  assert.ok(lc.isDraining, "draining after 401");
});

test("lifecycle: stop drains in-flight then deactivates (order)", async () => {
  const platform = new FakePlatform({ claim: { effectiveConfig: {} } });
  platform.feedMessages({ taskId: "task-x", tenantId: "tenant-A" });

  let released: () => void = () => {};
  const handler: TaskHandler = {
    execute: async () =>
      new Promise((resolve) => {
        released = () => resolve(taskSuccess());
      }),
  };

  const lc = new WorkerLifecycle({
    config: baseConfig,
    transport: platform.transport,
    consumer: platform.consumer,
    handler,
    logger: silentLogger,
    installSignalHandlers: false,
  });

  await lc.start();
  await new Promise((r) => setTimeout(r, 10));
  assert.equal(lc.inFlightCount, 1, "task is in flight");

  // start stop, then release the handler mid-drain
  const stopping = lc.stop(300);
  assert.equal(lc.isDraining, true);
  assert.ok(platform.consumer.wokeUp, "consumer woken on stop");
  setTimeout(() => released(), 20);
  await stopping;

  const ops = platform.transport.calls.map((c) => c.op);
  // report (task finished) must precede deactivate
  assert.ok(
    ops.indexOf("report") < ops.lastIndexOf("deactivate"),
    "report before deactivate",
  );
  assert.ok(ops.includes("deactivate"), "deactivated");
  assert.equal(lc.inFlightCount, 0, "drained");
});

test("lifecycle: refuses new messages once draining", async () => {
  const platform = new FakePlatform({ claim: { effectiveConfig: {} } });
  const handler: TaskHandler = { execute: async () => taskSuccess() };

  const lc = new WorkerLifecycle({
    config: baseConfig,
    transport: platform.transport,
    consumer: platform.consumer,
    handler,
    logger: silentLogger,
    installSignalHandlers: false,
  });

  await lc.start();
  await lc.stop(100);

  const claimsBefore = platform.transport.countOf("claim");
  // feed a message after draining and drain the fake consumer
  platform.feedMessages({ taskId: "late", tenantId: "tenant-A" });
  await platform.consumer.drain();
  assert.equal(
    platform.transport.countOf("claim"),
    claimsBefore,
    "no claim for messages arriving while draining",
  );
});

test("lifecycle: SIGTERM triggers graceful stop+deactivate", async () => {
  const platform = new FakePlatform({ claim: { effectiveConfig: {} } });
  const handler: TaskHandler = { execute: async () => taskSuccess() };

  const lc = new WorkerLifecycle({
    config: baseConfig,
    transport: platform.transport,
    consumer: platform.consumer,
    handler,
    logger: silentLogger,
    installSignalHandlers: true, // register the real SIGTERM handler
  });

  await lc.start();
  process.emit("SIGTERM");

  // wait for the async stop() kicked off by the handler to finish
  await new Promise((r) => setTimeout(r, 80));
  assert.equal(lc.fsm, "DRAINING");
  assert.ok(platform.transport.countOf("deactivate") >= 1, "deactivated via SIGTERM");
});
