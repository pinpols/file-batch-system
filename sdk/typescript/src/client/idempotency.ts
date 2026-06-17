import { ErrorCode } from "../protocol.ts";
import {
  taskFailure,
  type TaskContext,
  type TaskHandler,
  type TaskResult,
} from "./handler.ts";

export const IDEMPOTENT_IN_FLIGHT = "IDEMPOTENT_IN_FLIGHT";

export interface SdkIdempotencyEntity {
  result: TaskResult;
}

export interface SdkIdempotencyStore {
  tryAcquire(key: string, ttlMs?: number): Promise<boolean>;
  find(key: string): Promise<SdkIdempotencyEntity | undefined>;
  record(key: string, entity: SdkIdempotencyEntity, ttlMs?: number): Promise<void>;
  release(key: string): Promise<void>;
}

export type IdempotencyKeyResolver = (ctx: TaskContext) => string | Promise<string>;

export interface IdempotencyOptions {
  ttlMs?: number;
  keyResolver?: IdempotencyKeyResolver;
}

export const taskIdIdempotencyKey: IdempotencyKeyResolver = (ctx) => ctx.taskId;

export function withIdempotency(
  handler: TaskHandler,
  store: SdkIdempotencyStore | undefined,
  options: IdempotencyOptions = {},
): TaskHandler {
  if (!store) return handler;
  const keyResolver = options.keyResolver ?? taskIdIdempotencyKey;

  return {
    async execute(ctx: TaskContext): Promise<TaskResult> {
      let key: string;
      try {
        key = await keyResolver(ctx);
      } catch (error) {
        return taskFailure(ErrorCode.CONFIG_INVALID, `idempotency key failed: ${messageOf(error)}`);
      }
      if (!key) {
        return taskFailure(ErrorCode.CONFIG_INVALID, "idempotency key is empty");
      }

      let acquired: boolean;
      try {
        acquired = await store.tryAcquire(key, options.ttlMs);
      } catch (error) {
        return taskFailure(ErrorCode.EXECUTION_FAILED, `idempotency acquire failed: ${messageOf(error)}`);
      }

      if (!acquired) {
        try {
          const cached = await store.find(key);
          if (cached) return cached.result;
          return taskFailure(IDEMPOTENT_IN_FLIGHT, "idempotent execution is already in flight");
        } catch (error) {
          return taskFailure(ErrorCode.EXECUTION_FAILED, `idempotency lookup failed: ${messageOf(error)}`);
        }
      }

      try {
        const result = await handler.execute(ctx);
        if (!result.success) {
          await store.release(key);
          return result;
        }
        try {
          await store.record(key, { result }, options.ttlMs);
        } catch (error) {
          return taskFailure(ErrorCode.EXECUTION_FAILED, `idempotency record failed: ${messageOf(error)}`);
        }
        return result;
      } catch (error) {
        await store.release(key);
        throw error;
      }
    },
  };
}

export class NoopIdempotencyStore implements SdkIdempotencyStore {
  async tryAcquire(_key: string, _ttlMs?: number): Promise<boolean> {
    return true;
  }

  async find(_key: string): Promise<SdkIdempotencyEntity | undefined> {
    return undefined;
  }

  async record(_key: string, _entity: SdkIdempotencyEntity, _ttlMs?: number): Promise<void> {
    // intentionally empty
  }

  async release(_key: string): Promise<void> {
    // intentionally empty
  }
}

export class InMemoryIdempotencyStore implements SdkIdempotencyStore {
  #entries = new Map<string, { entity?: SdkIdempotencyEntity; expiresAt?: number }>();

  async tryAcquire(key: string, ttlMs?: number): Promise<boolean> {
    this.#deleteIfExpired(key);
    if (this.#entries.has(key)) return false;
    this.#entries.set(key, { expiresAt: expiresAt(ttlMs) });
    return true;
  }

  async find(key: string): Promise<SdkIdempotencyEntity | undefined> {
    this.#deleteIfExpired(key);
    return this.#entries.get(key)?.entity;
  }

  async record(key: string, entity: SdkIdempotencyEntity, ttlMs?: number): Promise<void> {
    this.#entries.set(key, { entity, expiresAt: expiresAt(ttlMs) });
  }

  async release(key: string): Promise<void> {
    this.#entries.delete(key);
  }

  #deleteIfExpired(key: string): void {
    const entry = this.#entries.get(key);
    if (!entry?.expiresAt || Date.now() < entry.expiresAt) return;
    this.#entries.delete(key);
  }
}

function expiresAt(ttlMs?: number): number | undefined {
  if (!ttlMs || ttlMs <= 0) return undefined;
  return Date.now() + ttlMs;
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
