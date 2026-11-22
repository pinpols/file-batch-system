import { test } from "node:test";
import assert from "node:assert/strict";
import {
  ErrorCode,
  IDEMPOTENT_IN_FLIGHT,
  InMemoryIdempotencyStore,
  taskFailure,
  taskSuccess,
  withIdempotency,
  withRetry,
  type SdkIdempotencyClaim,
  type SdkIdempotencyEntity,
  type SdkIdempotencyStore,
  type TaskContext,
  type TaskHandler,
} from "../src/index.ts";

function ctx(taskId = "task-1"): TaskContext {
  return {
    taskId,
    effectiveConfig: {},
    traceId: "",
    cancellation: {
      isCancellationRequested: false,
      markCancelled() {},
      onCancel(_cb: () => void) {},
    },
    progress: { report(_percent: number, _message?: string) {} },
    checkpoint() {
      throw new Error("not used");
    },
    async commit(_breakPosition: Record<string, unknown>) {},
  };
}

test("withRetry retries retryable TaskResult failures", async () => {
  let attempts = 0;
  const handler: TaskHandler = {
    async execute() {
      attempts += 1;
      return attempts < 3
        ? taskFailure(ErrorCode.EXECUTION_FAILED, "temporary")
        : taskSuccess({ attempts }, "ok");
    },
  };

  const result = await withRetry(handler, { maxAttempts: 3, initialDelayMs: 0 }).execute(ctx());

  assert.equal(result.success, true);
  assert.equal(result.outputs?.attempts, 3);
  assert.equal(attempts, 3);
});

test("withRetry stops on non-retryable TaskResult", async () => {
  let attempts = 0;
  const handler: TaskHandler = {
    async execute() {
      attempts += 1;
      return taskFailure(ErrorCode.CONFIG_INVALID, "bad config");
    },
  };

  const result = await withRetry(handler, { maxAttempts: 3, initialDelayMs: 0 }).execute(ctx());

  assert.equal(result.errorCode, ErrorCode.CONFIG_INVALID);
  assert.equal(attempts, 1);
});

test("withIdempotency caches successful result", async () => {
  const store = new InMemoryIdempotencyStore();
  let calls = 0;
  const handler = withIdempotency(
    {
      async execute() {
        calls += 1;
        return taskSuccess({ calls }, "done");
      },
    },
    store,
  );

  const first = await handler.execute(ctx("same"));
  const second = await handler.execute(ctx("same"));

  assert.equal(first.success, true);
  assert.equal(second.success, true);
  assert.equal(calls, 1);
  assert.equal(second.outputs?.calls, 1);
});

test("withIdempotency releases key after failed result", async () => {
  const store = new InMemoryIdempotencyStore();
  let calls = 0;
  const handler = withIdempotency(
    {
      async execute() {
        calls += 1;
        return calls === 1
          ? taskFailure(ErrorCode.EXECUTION_FAILED, "temporary")
          : taskSuccess(undefined, "ok");
      },
    },
    store,
  );

  assert.equal((await handler.execute(ctx("retry"))).success, false);
  assert.equal((await handler.execute(ctx("retry"))).success, true);
  assert.equal(calls, 2);
});

test("withIdempotency reports in-flight when no cached entity exists", async () => {
  const store = new InMemoryIdempotencyStore();
  assert.equal(await store.tryAcquire("busy"), true);
  const handler = withIdempotency(
    {
      async execute() {
        throw new Error("should not run");
      },
    },
    store,
    { keyResolver: () => "busy" },
  );

  const result = await handler.execute(ctx());

  assert.equal(result.success, false);
  assert.equal(result.errorCode, IDEMPOTENT_IN_FLIGHT);
});

class FailingRecordStore implements SdkIdempotencyStore {
  #delegate = new InMemoryIdempotencyStore();

  tryAcquire(key: string, ttlMs?: number): Promise<boolean> {
    return this.#delegate.tryAcquire(key, ttlMs);
  }

  find(key: string): Promise<SdkIdempotencyEntity | undefined> {
    return this.#delegate.find(key);
  }

  async record(_key: string, _entity: SdkIdempotencyEntity, _ttlMs?: number): Promise<void> {
    throw new Error("store down");
  }

  release(key: string): Promise<void> {
    return this.#delegate.release(key);
  }
}

test("withIdempotency maps record failure to EXECUTION_FAILED", async () => {
  const result = await withIdempotency(
    {
      async execute() {
        return taskSuccess(undefined, "ok");
      },
    },
    new FailingRecordStore(),
  ).execute(ctx());

  assert.equal(result.success, false);
  assert.equal(result.errorCode, ErrorCode.EXECUTION_FAILED);
});

test("stale claim cannot release or overwrite a newer successful result", async () => {
  let now = 1_000;
  const store = new InMemoryIdempotencyStore(() => now);

  const claimA = await store.acquire("owned", 10);
  assert.ok(claimA);
  now = 1_011;
  const claimB = await store.acquire("owned", 10);
  assert.ok(claimB);
  assert.equal(
    await store.recordOwned("owned", { result: taskSuccess({ winner: "B" }) }, claimB!, 1_000),
    true,
  );

  assert.equal(await store.releaseOwned("owned", claimA!), false);
  assert.equal(
    await store.recordOwned("owned", { result: taskSuccess({ winner: "A" }) }, claimA!, 1_000),
    false,
  );

  const cached = await store.find("owned");
  assert.deepEqual(cached?.result.outputs, { winner: "B" });
});

test("withIdempotency keeps working with a legacy four-method store", async () => {
  const entries = new Map<string, SdkIdempotencyEntity>();
  const legacyStore: SdkIdempotencyStore = {
    async tryAcquire(key) {
      if (entries.has(key)) return false;
      entries.set(key, { result: taskSuccess(undefined, "placeholder") });
      return true;
    },
    async find(key) {
      return entries.get(key);
    },
    async record(key, entity) {
      entries.set(key, entity);
    },
    async release(key) {
      entries.delete(key);
    },
  };
  let calls = 0;
  const handler = withIdempotency(
    {
      async execute() {
        calls += 1;
        return taskSuccess({ calls }, "done");
      },
    },
    legacyStore,
  );

  assert.equal((await handler.execute(ctx("legacy"))).success, true);
  assert.equal((await handler.execute(ctx("legacy"))).success, true);
  assert.equal(calls, 1);
});

test("a late executor cannot overwrite the newer executor result", async () => {
  let now = 1_000;
  const store = new InMemoryIdempotencyStore(() => now);
  let markStarted!: () => void;
  const started = new Promise<void>((resolve) => {
    markStarted = resolve;
  });
  let releaseA!: () => void;
  const gate = new Promise<void>((resolve) => {
    releaseA = resolve;
  });
  const handlerA = withIdempotency(
    {
      async execute() {
        markStarted();
        await gate;
        return taskSuccess({ winner: "A" }, "A");
      },
    },
    store,
    { keyResolver: () => "shared", ttlMs: 10 },
  );
  const handlerB = withIdempotency(
    {
      async execute() {
        return taskSuccess({ winner: "B" }, "B");
      },
    },
    store,
    { keyResolver: () => "shared", ttlMs: 10 },
  );

  const resultA = handlerA.execute(ctx("shared"));
  await started;
  now = 1_011;
  const resultB = await handlerB.execute(ctx("shared"));
  releaseA();
  assert.equal((await resultA).success, true);

  assert.deepEqual(resultB.outputs, { winner: "B" });
  const third = await handlerA.execute(ctx("shared"));
  assert.deepEqual(third.outputs, { winner: "B" });
});
