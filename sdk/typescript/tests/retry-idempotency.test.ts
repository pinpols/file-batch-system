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
  const handler = withIdempotency({
    async execute() {
      calls += 1;
      return taskSuccess({ calls }, "done");
    },
  }, store);

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
  const handler = withIdempotency({
    async execute() {
      calls += 1;
      return calls === 1
        ? taskFailure(ErrorCode.EXECUTION_FAILED, "temporary")
        : taskSuccess(undefined, "ok");
    },
  }, store);

  assert.equal((await handler.execute(ctx("retry"))).success, false);
  assert.equal((await handler.execute(ctx("retry"))).success, true);
  assert.equal(calls, 2);
});

test("withIdempotency reports in-flight when no cached entity exists", async () => {
  const store = new InMemoryIdempotencyStore();
  assert.equal(await store.tryAcquire("busy"), true);
  const handler = withIdempotency({
    async execute() {
      throw new Error("should not run");
    },
  }, store, { keyResolver: () => "busy" });

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
  const result = await withIdempotency({
    async execute() {
      return taskSuccess(undefined, "ok");
    },
  }, new FailingRecordStore()).execute(ctx());

  assert.equal(result.success, false);
  assert.equal(result.errorCode, ErrorCode.EXECUTION_FAILED);
});
