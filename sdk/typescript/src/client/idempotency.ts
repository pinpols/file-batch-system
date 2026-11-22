import { ErrorCode } from "../protocol.ts";
import { taskFailure, type TaskContext, type TaskHandler, type TaskResult } from "./handler.ts";

export const IDEMPOTENT_IN_FLIGHT = "IDEMPOTENT_IN_FLIGHT";

export interface SdkIdempotencyEntity {
  result: TaskResult;
}

/** 本轮执行权的持有凭据；只对仍然有效的持有者生效。 */
export interface SdkIdempotencyClaim {
  readonly token: string;
}

/** 旧四方法存储没有凭据：包装成固定令牌，仅用于兼容自定义存储。 */
export const LEGACY_IDEMPOTENCY_CLAIM: SdkIdempotencyClaim = Object.freeze({ token: "legacy" });

export interface SdkIdempotencyStore {
  tryAcquire(key: string, ttlMs?: number): Promise<boolean>;
  find(key: string): Promise<SdkIdempotencyEntity | undefined>;
  record(key: string, entity: SdkIdempotencyEntity, ttlMs?: number): Promise<void>;
  release(key: string): Promise<void>;

  /**
   * 取得执行权并返回本轮凭据；undefined 表示已有执行权或已缓存结果。
   * 可选：未实现时回退到 {@link tryAcquire} 的兼容路径。
   */
  acquire?(key: string, ttlMs?: number): Promise<SdkIdempotencyClaim | undefined>;
  /** 仅当凭据仍是当前持有者时写入；返回是否写入。 */
  recordOwned?(
    key: string,
    entity: SdkIdempotencyEntity,
    claim: SdkIdempotencyClaim,
    ttlMs?: number,
  ): Promise<boolean>;
  /** 仅当凭据仍是当前持有者时释放；返回是否释放。 */
  releaseOwned?(key: string, claim: SdkIdempotencyClaim): Promise<boolean>;
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
        const errorMessage = messageOf(error);
        return taskFailure(ErrorCode.CONFIG_INVALID, `idempotency key failed: ${errorMessage}`);
      }
      if (!key) {
        return taskFailure(ErrorCode.CONFIG_INVALID, "idempotency key is empty");
      }

      let claim: SdkIdempotencyClaim | undefined;
      try {
        claim = await acquireClaim(store, key, options.ttlMs);
      } catch (error) {
        const errorMessage = messageOf(error);
        return taskFailure(
          ErrorCode.EXECUTION_FAILED,
          `idempotency acquire failed: ${errorMessage}`,
        );
      }

      if (!claim) {
        try {
          const cached = await store.find(key);
          if (cached) return cached.result;
          return taskFailure(IDEMPOTENT_IN_FLIGHT, "idempotent execution is already in flight");
        } catch (error) {
          const errorMessage = messageOf(error);
          return taskFailure(
            ErrorCode.EXECUTION_FAILED,
            `idempotency lookup failed: ${errorMessage}`,
          );
        }
      }

      try {
        const result = await handler.execute(ctx);
        if (!result.success) {
          await releaseClaim(store, key, claim);
          return result;
        }
        try {
          // 凭据已失效（过期后被新执行者接管）时不覆盖新结果，但本次结果仍然有效。
          await recordClaim(store, key, { result }, claim, options.ttlMs);
        } catch (error) {
          const errorMessage = messageOf(error);
          return taskFailure(
            ErrorCode.EXECUTION_FAILED,
            `idempotency record failed: ${errorMessage}`,
          );
        }
        return result;
      } catch (error) {
        await releaseClaim(store, key, claim);
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
  #entries = new Map<
    string,
    { entity?: SdkIdempotencyEntity; expiresAt?: number; token?: string }
  >();
  #sequence = 0;
  readonly #now: () => number;

  constructor(now: () => number = Date.now) {
    this.#now = now;
  }

  async tryAcquire(key: string, ttlMs?: number): Promise<boolean> {
    return (await this.acquire(key, ttlMs)) !== undefined;
  }

  async acquire(key: string, ttlMs?: number): Promise<SdkIdempotencyClaim | undefined> {
    this.#deleteIfExpired(key);
    if (this.#entries.has(key)) return undefined;
    const token = `${this.#now()}-${++this.#sequence}`;
    this.#entries.set(key, { expiresAt: this.#expiresAt(ttlMs), token });
    return { token };
  }

  async find(key: string): Promise<SdkIdempotencyEntity | undefined> {
    this.#deleteIfExpired(key);
    return this.#entries.get(key)?.entity;
  }

  async record(key: string, entity: SdkIdempotencyEntity, ttlMs?: number): Promise<void> {
    const entry = this.#entries.get(key);
    this.#entries.set(key, {
      entity,
      expiresAt: this.#expiresAt(ttlMs),
      token: entry?.token,
    });
  }

  async release(key: string): Promise<void> {
    this.#entries.delete(key);
  }

  async recordOwned(
    key: string,
    entity: SdkIdempotencyEntity,
    claim: SdkIdempotencyClaim,
    ttlMs?: number,
  ): Promise<boolean> {
    this.#deleteIfExpired(key);
    const entry = this.#entries.get(key);
    if (entry?.token !== claim.token) return false;
    this.#entries.set(key, {
      entity,
      expiresAt: this.#expiresAt(ttlMs),
      token: claim.token,
    });
    return true;
  }

  async releaseOwned(key: string, claim: SdkIdempotencyClaim): Promise<boolean> {
    this.#deleteIfExpired(key);
    const entry = this.#entries.get(key);
    if (entry?.token !== claim.token) return false;
    this.#entries.delete(key);
    return true;
  }

  #deleteIfExpired(key: string): void {
    const entry = this.#entries.get(key);
    if (!entry?.expiresAt || this.#now() < entry.expiresAt) return;
    this.#entries.delete(key);
  }

  #expiresAt(ttlMs?: number): number | undefined {
    if (!ttlMs || ttlMs <= 0) return undefined;
    return this.#now() + ttlMs;
  }
}

async function acquireClaim(
  store: SdkIdempotencyStore,
  key: string,
  ttlMs?: number,
): Promise<SdkIdempotencyClaim | undefined> {
  if (typeof store.acquire === "function") {
    return store.acquire(key, ttlMs);
  }
  return (await store.tryAcquire(key, ttlMs)) ? LEGACY_IDEMPOTENCY_CLAIM : undefined;
}

async function recordClaim(
  store: SdkIdempotencyStore,
  key: string,
  entity: SdkIdempotencyEntity,
  claim: SdkIdempotencyClaim,
  ttlMs?: number,
): Promise<boolean> {
  if (typeof store.recordOwned === "function") {
    return store.recordOwned(key, entity, claim, ttlMs);
  }
  await store.record(key, entity, ttlMs);
  return true;
}

async function releaseClaim(
  store: SdkIdempotencyStore,
  key: string,
  claim: SdkIdempotencyClaim,
): Promise<void> {
  if (typeof store.releaseOwned === "function") {
    await store.releaseOwned(key, claim);
    return;
  }
  await store.release(key);
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
