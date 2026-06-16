/**
 * Consumer interface + onMessage pipeline (§1.2 / §1.9 / §A).
 *
 * The real kafkajs adapter is a documented future thin layer that implements
 * `Consumer` and forwards records to `MessagePipeline.onMessage`. This module
 * carries ZERO Kafka dependency — `FakeConsumer` scripts records for tests.
 *
 * Pipeline per record:
 *   1. JSON deserialize (ignore unknown fields, §A).
 *   2. classifySchemaVersion — unknown major (v3+) → reject, do NOT commit.
 *   3. tenant self-check (§1.9) — msg.tenantId === config.tenantId else drop+log.
 *   4. decideBackpressure — in-flight at cap → pause assignment.
 *   5. dispatch to the claim→execute→report flow (handled by lifecycle).
 */

import { classifySchemaVersion, decideBackpressure } from "../decide.ts";

/** A raw dispatch record from the broker (or a fake). */
export interface ConsumerRecord {
  /** raw JSON payload (UTF-8); pipeline deserializes. */
  value: string;
  /** opaque partition/offset bag for commit bookkeeping (optional). */
  meta?: Record<string, unknown>;
}

/** Decoded dispatch message (unknown fields ignored, §A). */
export interface DispatchMessage {
  taskId: string;
  tenantId: string;
  /** routing metadata: the consumer accepts only messages whose workerType it serves. */
  workerType: string;
  schemaVersion?: string | null;
  parameters?: Record<string, unknown>;
  runtimeAttributes?: { traceId?: string } & Record<string, unknown>;
  [k: string]: unknown;
}

/** Kafka assignment control the pipeline can request. */
export interface Assignment {
  pause(): void;
  resume(): void;
  isPaused(): boolean;
}

/** The consumer seam (real kafkajs adapter implements this). */
export interface Consumer {
  /** Begin subscription; deliver each record to the supplied pipeline. */
  start(onMessage: (r: ConsumerRecord) => Promise<void>): Promise<void>;
  /** Interrupt poll (maps to kafkajs `consumer.stop()` / wakeup). */
  wakeup(): Promise<void>;
  pause(): void;
  resume(): void;
  isPaused(): boolean;
}

/** Logger seam (default: console). */
export interface Logger {
  info(msg: string, ctx?: Record<string, unknown>): void;
  warn(msg: string, ctx?: Record<string, unknown>): void;
  error(msg: string, ctx?: Record<string, unknown>): void;
}

export const consoleLogger: Logger = {
  info: (m, c) => console.info(m, c ?? ""),
  warn: (m, c) => console.warn(m, c ?? ""),
  error: (m, c) => console.error(m, c ?? ""),
};

/** Outcome of running one record through the pipeline. */
export type PipelineOutcome =
  | { kind: "accepted"; message: DispatchMessage; committed: boolean }
  | { kind: "rejected-schema"; committed: false }
  | { kind: "dropped-tenant"; committed: false }
  | { kind: "not-for-worker"; committed: false }
  | { kind: "parse-error"; committed: false }
  | { kind: "backpressure"; message: DispatchMessage; committed: false };

export interface MessagePipelineDeps {
  tenantId: string;
  /**
   * workerTypes this worker serves; a message whose `workerType` is not in this
   * set is "not-for-us" and is NOT committed so another worker in the group can
   * reprocess it. Empty / omitted → serve all (no routing filter).
   */
  workerTypes?: readonly string[];
  /** current in-flight task count (live read). */
  inFlight: () => number;
  maxConcurrent: number;
  assignment: Assignment;
  logger?: Logger;
  /** invoked for an accepted, in-tenant, processable message. */
  onAccepted: (msg: DispatchMessage) => Promise<void>;
}

/**
 * Pure-ish pipeline: validates a record and either dispatches it or drops it.
 * "committed" mirrors the offset-commit decision (§A: reject/drop must NOT
 * commit so a fixed deploy can re-read).
 */
export class MessagePipeline {
  #deps: MessagePipelineDeps;
  #logger: Logger;
  #workerTypes: ReadonlySet<string> | undefined;

  constructor(deps: MessagePipelineDeps) {
    this.#deps = deps;
    this.#logger = deps.logger ?? consoleLogger;
    // undefined / empty → no routing filter (serve every workerType).
    this.#workerTypes =
      deps.workerTypes && deps.workerTypes.length > 0
        ? new Set(deps.workerTypes)
        : undefined;
  }

  async onMessage(record: ConsumerRecord): Promise<PipelineOutcome> {
    // 1. deserialize
    let msg: DispatchMessage;
    try {
      msg = JSON.parse(record.value) as DispatchMessage;
    } catch (e) {
      this.#logger.error("dispatch JSON parse failed; not committing", {
        error: String(e),
      });
      return { kind: "parse-error", committed: false };
    }

    // 2. schemaVersion (§A) — unknown major rejected, do not commit
    if (classifySchemaVersion(msg.schemaVersion) === "reject") {
      this.#logger.error("unknown schemaVersion major; rejecting, not committing", {
        schemaVersion: msg.schemaVersion,
        taskId: msg.taskId,
      });
      return { kind: "rejected-schema", committed: false };
    }

    // 3. tenant self-check (§1.9) — last line of defense against ACL drift
    if (msg.tenantId !== this.#deps.tenantId) {
      this.#logger.error("foreign tenant message; dropping, not committing", {
        expected: this.#deps.tenantId,
        actual: msg.tenantId,
        taskId: msg.taskId,
      });
      return { kind: "dropped-tenant", committed: false };
    }

    // 3b. workerType routing — a message not meant for this worker's type must
    // NOT be committed, so another worker in the group reprocesses it.
    if (this.#workerTypes && !this.#workerTypes.has(msg.workerType)) {
      this.#logger.warn("message workerType not served here; not-for-us, not committing", {
        served: [...this.#workerTypes],
        actual: msg.workerType,
        taskId: msg.taskId,
      });
      return { kind: "not-for-worker", committed: false };
    }

    // 4. capacity backpressure
    const bp = decideBackpressure(this.#deps.inFlight(), this.#deps.maxConcurrent);
    if (bp.action === "backpressure") {
      this.#deps.assignment.pause();
      this.#logger.warn("at capacity; pausing assignment (backpressure)", {
        taskId: msg.taskId,
      });
      return { kind: "backpressure", message: msg, committed: false };
    }

    // 5. dispatch to claim→execute→report
    await this.#deps.onAccepted(msg);
    return { kind: "accepted", message: msg, committed: true };
  }
}

/**
 * In-memory consumer for tests: feeds scripted records, records wakeup/pause.
 * No Kafka client.
 */
export class FakeConsumer implements Consumer {
  #records: ConsumerRecord[];
  #paused = false;
  #wokeUp = false;
  #handler?: (r: ConsumerRecord) => Promise<void>;

  constructor(records: ConsumerRecord[] = []) {
    this.#records = records;
  }

  /** Queue more records (e.g. mid-test). */
  feed(...records: ConsumerRecord[]): void {
    this.#records.push(...records);
  }

  async start(onMessage: (r: ConsumerRecord) => Promise<void>): Promise<void> {
    this.#handler = onMessage;
    // drain the scripted queue immediately (cooperative, awaited)
    await this.drain();
  }

  /** Deliver all currently-queued records through the handler. */
  async drain(): Promise<void> {
    if (!this.#handler) return;
    while (this.#records.length > 0 && !this.#wokeUp) {
      const rec = this.#records.shift()!;
      await this.#handler(rec);
    }
  }

  async wakeup(): Promise<void> {
    this.#wokeUp = true;
  }

  get wokeUp(): boolean {
    return this.#wokeUp;
  }

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

/** Default Assignment backed by a Consumer's pause/resume. */
export function assignmentOf(consumer: Consumer): Assignment {
  return {
    pause: () => consumer.pause(),
    resume: () => consumer.resume(),
    isPaused: () => consumer.isPaused(),
  };
}
