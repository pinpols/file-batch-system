/**
 * ADR-037 P1–P3 — checkpoint / resume + reliable-commit primitives.
 *
 * This is a **thin BYO SDK**: there are no typed templates here. We expose the
 * resume primitives on the task context so tenant handlers can drive their own
 * long-running, large-volume `execute(ctx)` loops:
 *
 *   - P1 `SdkCheckpoint` / `SdkCheckpointState` — the break-point protocol. The
 *     SDK defines the *semantics*; the tenant owns *persistence* (a control
 *     table / KV / object store). The break-point is the **data's own primary
 *     key / range key**, never an offset.
 *   - P2 `ctx.commit(breakPosition)` — one call that atomically saves the
 *     checkpoint and emits a rate-limited progress report.
 *   - P3 cooperative cancel — after a successful commit, if the task is
 *     cancelled we throw `SdkTaskStopped` (carrying the break position) so the
 *     worker maps it to a *cancelled* terminal report, not a failure.
 *
 * The Java contract this mirrors (ADR-037 §决策一/二/三): `Optional<...> load`
 * becomes `Promise<... | null>`, `void save/commit` become `Promise<void>`.
 */

import type { CancellationSignal, ProgressReporter } from "./handler.ts";

/**
 * A persisted resume point for one task (ADR-037 §决策一).
 *
 * `breakPosition` is the data's own coordinate (primary key / range key) of the
 * last fully-processed record — NOT a Kafka offset and NOT a row count. Resume
 * means "continue *after* this key", so it stays valid across re-dispatch.
 */
export interface SdkCheckpointState {
  /** Last processed record's key / range — the resume coordinate. */
  breakPosition: Record<string, unknown>;
  /** Cumulative succeeded record count (restored on resume; never reset). */
  succeedCount: number;
  /** Cumulative failed record count (restored on resume). */
  failCount: number;
  /** Idempotency flag: a completed task is skipped wholesale on resume. */
  completed: boolean;
}

/**
 * Break-point persistence protocol (ADR-037 §决策一).
 *
 * **Same-transaction requirement (ADR-037 §决策二, hard constraint):** a real
 * implementation MUST persist the checkpoint in the *same transaction* as the
 * business data it describes. If business data commits but the checkpoint does
 * not (or vice-versa), a crash tears the two apart and resume either reprocesses
 * or drops records. For a JDBC business store this means updating the checkpoint
 * row and committing the business work on one `Connection` / one `commit()`.
 * The SDK cannot enforce this for you — code review must.
 *
 * The in-memory default ({@link InMemorySdkCheckpoint}) is for tests / examples
 * only; it has no transactional coupling to any business store.
 */
export interface SdkCheckpoint {
  /** Read back the last checkpoint; first run resolves to `null`. */
  load(taskId: string): Promise<SdkCheckpointState | null>;
  /** Persist the checkpoint (see same-transaction requirement above). */
  save(taskId: string, state: SdkCheckpointState): Promise<void>;
}

/**
 * In-memory `SdkCheckpoint` for tests and examples. NOT for production: it has
 * no same-transaction coupling to a business store (see {@link SdkCheckpoint}).
 */
export class InMemorySdkCheckpoint implements SdkCheckpoint {
  readonly #store = new Map<string, SdkCheckpointState>();

  load(taskId: string): Promise<SdkCheckpointState | null> {
    const state = this.#store.get(taskId);
    // hand back a copy so callers can't mutate our stored snapshot in place
    return Promise.resolve(
      state
        ? { ...state, breakPosition: { ...state.breakPosition } }
        : null,
    );
  }

  save(taskId: string, state: SdkCheckpointState): Promise<void> {
    this.#store.set(taskId, {
      ...state,
      breakPosition: { ...state.breakPosition },
    });
    return Promise.resolve();
  }
}

/**
 * Thrown by `ctx.commit(...)` after a successful commit when the task has been
 * cancelled (ADR-037 §决策三). Carries the safe-point `breakPosition` already
 * committed, so cancellation always stops *between* batches — never mid-batch.
 *
 * Tenant code MUST NOT swallow this: the worker catches it and reports a
 * `CANCELLED` terminal state instead of a failure.
 */
export class SdkTaskStopped extends Error {
  readonly breakPosition: Record<string, unknown>;

  constructor(breakPosition: Record<string, unknown>) {
    super("SDK task stopped at checkpoint (cancellation requested)");
    this.name = "SdkTaskStopped";
    this.breakPosition = { ...breakPosition };
    // keep `instanceof` working when transpiled down to ES5-ish targets
    Object.setPrototypeOf(this, SdkTaskStopped.prototype);
  }
}

/** Tuning for {@link ResumeSupport} (ADR-037 §决策二 progress throttle). */
export interface ResumeOptions {
  /**
   * Emit a progress report every Nth commit (counter % reportInterval === 0).
   * Must be >= 1. Defaults to 1 (report on every commit).
   */
  reportInterval?: number;
  /**
   * When true, the tenant drives progress reporting itself; `commit` skips the
   * rate-limited auto-report entirely. Defaults to false.
   */
  selfReport?: boolean;
}

/**
 * Backs the `checkpoint()` / `commit()` primitives the worker grafts onto the
 * task context. One instance per running task.
 */
export class ResumeSupport {
  readonly #taskId: string;
  readonly #checkpoint: SdkCheckpoint;
  readonly #progress: ProgressReporter;
  readonly #cancellation: CancellationSignal;
  readonly #reportInterval: number;
  readonly #selfReport: boolean;

  #commitCounter = 0;
  #succeedCount = 0;
  #failCount = 0;

  constructor(deps: {
    taskId: string;
    checkpoint: SdkCheckpoint;
    progress: ProgressReporter;
    cancellation: CancellationSignal;
    options?: ResumeOptions;
  }) {
    this.#taskId = deps.taskId;
    this.#checkpoint = deps.checkpoint;
    this.#progress = deps.progress;
    this.#cancellation = deps.cancellation;
    const interval = deps.options?.reportInterval ?? 1;
    this.#reportInterval = interval >= 1 ? Math.floor(interval) : 1;
    this.#selfReport = deps.options?.selfReport ?? false;
  }

  /** The checkpoint store this task resumes from / commits to. */
  checkpoint(): SdkCheckpoint {
    return this.#checkpoint;
  }

  /**
   * Seed cumulative counts after a resume `load()`, so progress does not reset.
   * Tenant code calls this once at the top of `execute` with the restored state.
   */
  restoreCounts(succeedCount: number, failCount: number): void {
    this.#succeedCount = succeedCount;
    this.#failCount = failCount;
  }

  /** Record this batch's outcome before the next `commit`. */
  recordBatch(succeeded: number, failed: number): void {
    this.#succeedCount += succeeded;
    this.#failCount += failed;
  }

  /**
   * Commit one business batch (ADR-037 §决策二/三):
   *   1. save the checkpoint (the real impl pairs this with the business
   *      transaction — see {@link SdkCheckpoint});
   *   2. emit a rate-limited progress report (unless `selfReport`);
   *   3. if the task is cancelled, throw {@link SdkTaskStopped} from the
   *      just-committed safe point.
   */
  async commit(breakPosition: Record<string, unknown>): Promise<void> {
    await this.#checkpoint.save(this.#taskId, {
      breakPosition,
      succeedCount: this.#succeedCount,
      failCount: this.#failCount,
      completed: false,
    });

    this.#commitCounter += 1;
    if (
      !this.#selfReport &&
      this.#commitCounter % this.#reportInterval === 0
    ) {
      const processed = this.#succeedCount + this.#failCount;
      this.#progress.report(
        processed,
        `committed ${this.#succeedCount} ok / ${this.#failCount} failed`,
      );
    }

    if (this.#cancellation.isCancellationRequested) {
      throw new SdkTaskStopped(breakPosition);
    }
  }
}
