/**
 * Handler SPI for tenant task code (wire-protocol §4 task loop, §B field names).
 *
 * A tenant implements `TaskHandler.execute(ctx)` and returns a `TaskResult`.
 * The runtime supplies a `TaskContext` carrying the effective config, traceId,
 * a `CancellationSignal` (flipped by the lease-renewal scheduler when the
 * platform sets `cancelRequested`), and a `ProgressReporter`.
 */

import { ErrorCode } from "./protocol.ts";

/**
 * Cooperative cancellation token. The lease-renewal scheduler calls
 * `markCancelled()` when a renew response carries `cancelRequested=true`; the
 * handler polls `isCancellationRequested` at safe checkpoints and bails out.
 */
export interface CancellationSignal {
  readonly isCancellationRequested: boolean;
  markCancelled(): void;
  /** Register a callback fired once when cancellation is first requested. */
  onCancel(cb: () => void): void;
}

/** Default in-memory CancellationSignal. */
export class SimpleCancellationSignal implements CancellationSignal {
  #cancelled = false;
  #callbacks: Array<() => void> = [];

  get isCancellationRequested(): boolean {
    return this.#cancelled;
  }

  markCancelled(): void {
    if (this.#cancelled) return;
    this.#cancelled = true;
    for (const cb of this.#callbacks) {
      try {
        cb();
      } catch {
        // a stray cancel callback must never break the scheduler
      }
    }
    this.#callbacks = [];
  }

  onCancel(cb: () => void): void {
    if (this.#cancelled) {
      cb();
      return;
    }
    this.#callbacks.push(cb);
  }
}

/** Progress sink. Real impl forwards to logs / metrics; default is a no-op. */
export interface ProgressReporter {
  /** @param percent 0..100 @param message optional human note */
  report(percent: number, message?: string): void;
}

/** No-op progress reporter (records nothing). */
export class NoopProgressReporter implements ProgressReporter {
  report(_percent: number, _message?: string): void {
    // intentionally empty
  }
}

/** Execution context handed to a handler for one task. */
export interface TaskContext {
  readonly taskId: string;
  /** EffectiveTaskConfig snapshot returned by claim. */
  readonly effectiveConfig: Record<string, unknown>;
  /** runtimeAttributes.traceId for OTel correlation (may be empty). */
  readonly traceId: string;
  readonly cancellation: CancellationSignal;
  readonly progress: ProgressReporter;
}

/**
 * Task result. **Field names are a red line (wire-protocol §B):** the platform
 * `TaskExecutionReportDto` reads exactly `errorCode` / `outputs` /
 * `resultSummary`; any other name is silently dropped.
 */
export interface TaskResult {
  success: boolean;
  errorCode?: ErrorCode | string;
  outputs?: Record<string, unknown>;
  resultSummary?: string;
}

/** Build a success result. */
export function taskSuccess(
  outputs?: Record<string, unknown>,
  resultSummary?: string,
): TaskResult {
  const r: TaskResult = { success: true };
  if (outputs !== undefined) r.outputs = outputs;
  if (resultSummary !== undefined) r.resultSummary = resultSummary;
  return r;
}

/** Build a failure result. */
export function taskFailure(
  errorCode: ErrorCode | string = ErrorCode.EXECUTION_FAILED,
  resultSummary?: string,
  outputs?: Record<string, unknown>,
): TaskResult {
  const r: TaskResult = { success: false, errorCode };
  if (resultSummary !== undefined) r.resultSummary = resultSummary;
  if (outputs !== undefined) r.outputs = outputs;
  return r;
}

/** Tenant task SPI. One handler instance may serve many concurrent tasks. */
export interface TaskHandler {
  execute(ctx: TaskContext): Promise<TaskResult>;
}
