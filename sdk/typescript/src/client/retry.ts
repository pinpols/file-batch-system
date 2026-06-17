import { ErrorCode } from "../protocol.ts";
import type { TaskContext, TaskHandler, TaskResult } from "./handler.ts";

export interface RetryPolicy {
  maxAttempts?: number;
  initialDelayMs?: number;
  backoffMultiplier?: number;
  shouldRetryResult?: (result: TaskResult, attempt: number) => boolean;
  shouldRetryError?: (error: unknown, attempt: number) => boolean;
  sleep?: (delayMs: number) => Promise<void>;
}

export function defaultShouldRetryResult(result: TaskResult): boolean {
  return !result.success && (
    result.errorCode === ErrorCode.EXECUTION_FAILED ||
    result.errorCode === ErrorCode.TIMEOUT ||
    result.errorCode === ErrorCode.RESOURCE_EXHAUSTED
  );
}

export function withRetry(handler: TaskHandler, policy: RetryPolicy = {}): TaskHandler {
  const maxAttempts = Math.max(1, policy.maxAttempts ?? 3);
  const backoffMultiplier = policy.backoffMultiplier && policy.backoffMultiplier > 0
    ? policy.backoffMultiplier
    : 1;
  const shouldRetryResult = policy.shouldRetryResult ?? defaultShouldRetryResult;
  const shouldRetryError = policy.shouldRetryError ?? (() => true);
  const sleep = policy.sleep ?? defaultSleep;

  return {
    async execute(ctx: TaskContext): Promise<TaskResult> {
      let delayMs = Math.max(0, policy.initialDelayMs ?? 100);
      for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
        try {
          const result = await handler.execute(ctx);
          if (result.success || attempt === maxAttempts || !shouldRetryResult(result, attempt)) {
            return result;
          }
        } catch (error) {
          if (attempt === maxAttempts || !shouldRetryError(error, attempt)) {
            throw error;
          }
        }

        if (delayMs > 0) {
          await sleep(delayMs);
          delayMs = Math.round(delayMs * backoffMultiplier);
        }
      }

      throw new Error("retry loop exhausted without a terminal result");
    },
  };
}

async function defaultSleep(delayMs: number): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, delayMs));
}
