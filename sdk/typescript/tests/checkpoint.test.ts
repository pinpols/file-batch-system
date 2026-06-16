/**
 * ADR-037 P1–P3 — checkpoint/resume + reliable-commit primitive tests.
 *
 *   - P1: in-memory checkpoint load/save round-trip; resume skips completed.
 *   - P2: commit saves checkpoint + reports on the configured interval.
 *   - P3: commit throws SdkTaskStopped once cancelled; worker maps it to a
 *         CANCELLED terminal report (not a failure).
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  InMemorySdkCheckpoint,
  ResumeSupport,
  SdkTaskStopped,
  type SdkCheckpointState,
} from "../src/client/checkpoint.ts";
import {
  SimpleCancellationSignal,
  NoopProgressReporter,
  type ProgressReporter,
  type TaskContext,
  type TaskHandler,
} from "../src/client/handler.ts";
import { WorkerLifecycle } from "../src/client/lifecycle.ts";
import { FakePlatform } from "../src/client/testkit.ts";

const silentLogger = { info: () => {}, warn: () => {}, error: () => {} };
const baseConfig = { tenantId: "tenant-A", workerCode: "w1", maxConcurrent: 4 };

test("checkpoint: in-memory load/save round-trip; first run is null", async () => {
  const cp = new InMemorySdkCheckpoint();
  assert.equal(await cp.load("task-1"), null, "first run -> null");

  const state: SdkCheckpointState = {
    breakPosition: { id: 100 },
    succeedCount: 90,
    failCount: 10,
    completed: false,
  };
  await cp.save("task-1", state);

  const loaded = await cp.load("task-1");
  assert.deepEqual(loaded, state);

  // load returns a copy: mutating it must not corrupt the stored snapshot
  loaded!.breakPosition.id = 999;
  loaded!.succeedCount = 0;
  const reloaded = await cp.load("task-1");
  assert.deepEqual(reloaded!.breakPosition, { id: 100 });
  assert.equal(reloaded!.succeedCount, 90);
});

test("checkpoint: resume skips a completed task (idempotency)", async () => {
  const cp = new InMemorySdkCheckpoint();
  await cp.save("task-done", {
    breakPosition: { id: 5000 },
    succeedCount: 5000,
    failCount: 0,
    completed: true,
  });

  // mirrors the resume preamble in ADR-037 §决策一
  const state = await cp.load("task-done");
  const shouldSkip = state?.completed === true;
  assert.equal(shouldSkip, true, "completed task is skipped wholesale on resume");
});

test("commit: reports only on the configured interval (counter % reportInterval)", async () => {
  const reports: Array<{ value: number; msg?: string }> = [];
  const progress: ProgressReporter = {
    report: (value, msg) => reports.push({ value, msg }),
  };
  const cp = new InMemorySdkCheckpoint();
  const resume = new ResumeSupport({
    taskId: "task-int",
    checkpoint: cp,
    progress,
    cancellation: new SimpleCancellationSignal(),
    options: { reportInterval: 3 },
  });

  for (let i = 1; i <= 6; i++) {
    resume.recordBatch(1, 0);
    await resume.commit({ id: i });
    // every commit persists the checkpoint regardless of report throttle
    assert.deepEqual((await cp.load("task-int"))!.breakPosition, { id: i });
  }

  // reported on commit #3 and #6 only
  assert.equal(reports.length, 2);
  assert.equal(reports[0]!.value, 3, "3 processed by the first report");
  assert.equal(reports[1]!.value, 6, "6 processed by the second report");
});

test("commit: selfReport disables the auto progress report", async () => {
  let reported = 0;
  const progress: ProgressReporter = { report: () => { reported += 1; } };
  const resume = new ResumeSupport({
    taskId: "task-self",
    checkpoint: new InMemorySdkCheckpoint(),
    progress,
    cancellation: new SimpleCancellationSignal(),
    options: { selfReport: true, reportInterval: 1 },
  });

  resume.recordBatch(1, 0);
  await resume.commit({ id: 1 });
  assert.equal(reported, 0, "selfReport suppresses auto reporting");
});

test("commit: restoreCounts keeps progress non-zero across resume", async () => {
  const cp = new InMemorySdkCheckpoint();
  const resume = new ResumeSupport({
    taskId: "task-resume",
    checkpoint: cp,
    progress: new NoopProgressReporter(),
    cancellation: new SimpleCancellationSignal(),
  });
  resume.restoreCounts(90, 10);

  resume.recordBatch(5, 0);
  await resume.commit({ id: 200 });

  const saved = (await cp.load("task-resume"))!;
  assert.equal(saved.succeedCount, 95);
  assert.equal(saved.failCount, 10);
});

test("commit: throws SdkTaskStopped from the safe point when cancelled", async () => {
  const cp = new InMemorySdkCheckpoint();
  const cancellation = new SimpleCancellationSignal();
  const resume = new ResumeSupport({
    taskId: "task-cancel",
    checkpoint: cp,
    progress: new NoopProgressReporter(),
    cancellation,
  });

  // not cancelled yet -> commit returns cleanly
  resume.recordBatch(1, 0);
  await resume.commit({ id: 1 });

  // cancel, then the next commit throws AFTER persisting the checkpoint
  cancellation.markCancelled();
  resume.recordBatch(1, 0);
  await assert.rejects(
    () => resume.commit({ id: 2 }),
    (err: unknown) => {
      assert.ok(err instanceof SdkTaskStopped);
      assert.deepEqual(err.breakPosition, { id: 2 });
      return true;
    },
  );
  // the safe-point checkpoint was committed before the throw
  assert.deepEqual((await cp.load("task-cancel"))!.breakPosition, { id: 2 });
});

test("worker: maps SdkTaskStopped to a CANCELLED terminal report (not a failure)", async () => {
  const platform = new FakePlatform({ claim: { effectiveConfig: {} } });
  platform.feedMessages({ taskId: "task-stop", tenantId: "tenant-A" });

  // handler commits one batch, the lease scheduler "cancels", next commit stops
  const handler: TaskHandler = {
    execute: async (ctx: TaskContext) => {
      await ctx.commit({ id: 1 });
      ctx.cancellation.markCancelled();
      // tenant code must NOT swallow this; it propagates to the worker
      await ctx.commit({ id: 2 });
      // unreachable
      return { success: true };
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
  await new Promise((r) => setTimeout(r, 20));

  const report = platform.transport.calls.find((c) => c.op === "report")!;
  const body = report.args[1] as Record<string, unknown>;
  assert.equal(body.success, false, "cancelled is a non-success terminal state");
  assert.equal(body.errorCode, "CANCELLED", "mapped to CANCELLED, not EXECUTION_FAILED");
  assert.deepEqual(
    (body.outputs as Record<string, unknown>).breakPosition,
    { id: 2 },
    "carries the committed safe-point break position",
  );

  await lc.stop(200);
});
