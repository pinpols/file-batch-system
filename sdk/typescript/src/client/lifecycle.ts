/**
 * WorkerLifecycle (§1.5 FSM + §1.6 graceful stop).
 *
 * 4-state FSM (NORMAL / DEGRADED / PAUSED / DRAINING). `start()` registers,
 * starts the schedulers, then subscribes the consumer. `stop(timeoutMs)` sets
 * DRAINING, wakes the consumer, drains in-flight within timeout*0.4, awaits the
 * executor within timeout*0.6, then deactivates (wire-protocol §4 stop order).
 *
 * §4 Node pits handled:
 *   - a global `unhandledRejection` handler so a stray promise rejection cannot
 *     hang SIGTERM.
 *   - SIGTERM → stop(30000) (K8s terminationGracePeriodSeconds default).
 */

import {
  decideBackpressure,
  decideRegister,
  newIdempotencyKey,
  planStop,
} from "../decide.ts";
import type { FsmState, KafkaAction } from "../protocol.ts";
import { FatalTransportError, type ReportBody, type Transport } from "./transport.ts";
import {
  HeartbeatScheduler,
  LeaseRenewalScheduler,
  type InFlightTask,
} from "./scheduler.ts";
import {
  MessagePipeline,
  assignmentOf,
  consoleLogger,
  type Consumer,
  type ConsumerRecord,
  type DispatchMessage,
  type Logger,
} from "./consumer.ts";
import {
  SimpleCancellationSignal,
  NoopProgressReporter,
  type TaskContext,
  type TaskHandler,
  type TaskResult,
} from "./handler.ts";
import {
  InMemorySdkCheckpoint,
  ResumeSupport,
  SdkTaskStopped,
  type SdkCheckpoint,
  type ResumeOptions,
} from "./checkpoint.ts";
import { SensitiveDataValidator } from "./sensitive.ts";
import { ErrorCode } from "../protocol.ts";
import { SUPPORTED_SCHEMA_VERSIONS } from "../constants.ts";

let unhandledRejectionInstalled = false;

/** Install a process-wide unhandledRejection guard exactly once (§4 pit). */
export function installUnhandledRejectionGuard(logger: Logger = consoleLogger): void {
  if (unhandledRejectionInstalled) return;
  unhandledRejectionInstalled = true;
  process.on("unhandledRejection", (reason) => {
    logger.error("unhandledRejection swallowed (would otherwise hang SIGTERM)", {
      reason: String(reason),
    });
  });
}

export interface WorkerConfig {
  tenantId: string;
  workerCode: string;
  maxConcurrent: number;
  /**
   * workerTypes this worker serves (== the taskTypes its handler accepts). A
   * dispatch message whose `workerType` is not served is not-for-us and is not
   * committed. Omitted / empty → serve all (no routing filter).
   */
  workerTypes?: readonly string[];
  registerBody?: Record<string, unknown>;
  buildHeartbeatBody?: () => Record<string, unknown>;
  buildRenewBody?: (taskId: string) => Record<string, unknown>;
}

export interface WorkerLifecycleDeps {
  config: WorkerConfig;
  transport: Transport;
  consumer: Consumer;
  handler: TaskHandler;
  heartbeatIntervalMs?: number;
  leaseRenewIntervalMs?: number;
  logger?: Logger;
  validator?: SensitiveDataValidator;
  /** test seam: avoid registering a real SIGTERM listener. */
  installSignalHandlers?: boolean;
  /**
   * ADR-037 §决策一 — per-task break-point store factory. The real tenant impl
   * persists checkpoints in the same transaction as its business data (see
   * `SdkCheckpoint`). Defaults to an in-memory store (tests / examples only).
   */
  checkpointFactory?: (taskId: string) => SdkCheckpoint;
  /** ADR-037 §决策二 — progress-throttle / self-report tuning for `ctx.commit`. */
  resumeOptions?: ResumeOptions;
}

interface TrackedTask extends InFlightTask {
  promise: Promise<void>;
  /** ADR-014 invocation token from the claim response; echoed on renew + report. */
  partitionInvocationId?: string | null;
}

export class WorkerLifecycle {
  #cfg: WorkerConfig;
  #transport: Transport;
  #consumer: Consumer;
  #handler: TaskHandler;
  #logger: Logger;
  #validator: SensitiveDataValidator;
  #checkpointFactory: (taskId: string) => SdkCheckpoint;
  #resumeOptions?: ResumeOptions;

  #fsm: FsmState = "NORMAL";
  #draining = false;
  #inFlight = new Map<string, TrackedTask>();
  #heartbeat: HeartbeatScheduler;
  #leaseRenewal: LeaseRenewalScheduler;
  #pipeline: MessagePipeline;
  #sigtermHandler?: () => void;

  constructor(deps: WorkerLifecycleDeps) {
    this.#cfg = deps.config;
    this.#transport = deps.transport;
    this.#consumer = deps.consumer;
    this.#handler = deps.handler;
    this.#logger = deps.logger ?? consoleLogger;
    this.#validator = deps.validator ?? new SensitiveDataValidator();
    this.#checkpointFactory =
      deps.checkpointFactory ?? (() => new InMemorySdkCheckpoint());
    this.#resumeOptions = deps.resumeOptions;

    this.#heartbeat = new HeartbeatScheduler(
      this.#transport,
      {
        workerCode: this.#cfg.workerCode,
        // WorkerHeartbeatDto requires [tenantId, workerCode, status, heartbeatAt].
        buildBody:
          this.#cfg.buildHeartbeatBody ??
          (() => ({
            tenantId: this.#cfg.tenantId,
            workerCode: this.#cfg.workerCode,
            status: this.#draining ? "DRAINING" : "RUNNING",
            heartbeatAt: new Date().toISOString(),
            currentLoad: this.#inFlight.size,
          })),
        setFsm: (s) => this.#setFsm(s),
        applyKafka: (a) => this.#applyKafka(a),
        onDrain: () => void this.stop(30_000),
        setMaxConcurrent: (n) => {
          this.#cfg.maxConcurrent = n;
        },
        logger: this.#logger,
      },
      deps.heartbeatIntervalMs ?? 30_000,
    );

    this.#leaseRenewal = new LeaseRenewalScheduler(
      this.#transport,
      {
        inFlight: () => [...this.#inFlight.values()],
        // TaskClaimRequest (claim/renew shared) requires [tenantId, workerId];
        // taskId is in the URL. Echo the ADR-014 invocation token when present.
        buildBody:
          this.#cfg.buildRenewBody ??
          ((taskId) => ({
            tenantId: this.#cfg.tenantId,
            workerId: this.#cfg.workerCode,
            partitionInvocationId:
              this.#inFlight.get(taskId)?.partitionInvocationId ?? null,
          })),
        dropTask: (taskId) => this.#inFlight.delete(taskId),
        logger: this.#logger,
      },
      deps.leaseRenewIntervalMs ?? 60_000,
    );

    this.#pipeline = new MessagePipeline({
      tenantId: this.#cfg.tenantId,
      workerTypes: this.#cfg.workerTypes,
      inFlight: () => this.#inFlight.size,
      maxConcurrent: this.#cfg.maxConcurrent,
      assignment: assignmentOf(this.#consumer),
      logger: this.#logger,
      onAccepted: (msg) => this.#handleAccepted(msg),
    });

    if (deps.installSignalHandlers !== false) {
      installUnhandledRejectionGuard(this.#logger);
      this.#sigtermHandler = () => {
        this.#logger.info("SIGTERM received; graceful stop(30000)");
        void this.stop(30_000);
      };
      process.on("SIGTERM", this.#sigtermHandler);
    }
  }

  get fsm(): FsmState {
    return this.#fsm;
  }

  get isDraining(): boolean {
    return this.#draining;
  }

  get inFlightCount(): number {
    return this.#inFlight.size;
  }

  #setFsm(state: FsmState): void {
    if (this.#fsm === state) return;
    this.#logger.info("FSM transition", { from: this.#fsm, to: state });
    this.#fsm = state;
    if (state === "DRAINING") this.#draining = true;
  }

  #applyKafka(action: KafkaAction): void {
    switch (action) {
      case "pause":
        this.#consumer.pause();
        break;
      case "resume":
        this.#consumer.resume();
        break;
      case "subscribe":
      case "wakeup":
      case "none":
      case "drop-message":
        break;
    }
  }

  /** start(): register → start schedulers → subscribe consumer (§4 order). */
  async start(): Promise<void> {
    // register body credential check (§1.8 register path → throw on leak).
    // WorkerHeartbeatDto requires [tenantId, workerCode, status, heartbeatAt].
    const regBody = this.#cfg.registerBody ?? {
      tenantId: this.#cfg.tenantId,
      workerCode: this.#cfg.workerCode,
      // ADR-035 §2: SDK self-hosted workers register under the fixed "sdk-self-hosted"
      // group. The platform requires it (worker_registry.worker_group is NOT NULL) and
      // selects SDK workers by it; omitting it makes register fail with HTTP 500. The
      // decision-core (decide.ts) already encodes this value; the default body lacked it.
      workerGroup: "sdk-self-hosted",
      status: "RUNNING",
      heartbeatAt: new Date().toISOString(),
      // #536 register-time protocol-version gate: advertise the SDK's current
      // major (last of SUPPORTED_SCHEMA_VERSIONS). Register only — heartbeat null.
      protocolVersion:
        SUPPORTED_SCHEMA_VERSIONS[SUPPORTED_SCHEMA_VERSIONS.length - 1],
    };
    this.#validator.assertRegisterBody(regBody);

    const ack = await this.#transport.register(regBody);
    const decision = decideRegister(ack.idempotent);
    this.#setFsm(decision.fsmTransition);
    this.#logger.info("registered", { idempotent: ack.idempotent });

    this.#heartbeat.start();
    this.#leaseRenewal.start();

    // subscribe last so no message arrives before schedulers are live
    await this.#consumer.start((r: ConsumerRecord) =>
      this.#onRecord(r),
    );
  }

  async #onRecord(record: ConsumerRecord): Promise<void> {
    if (this.#draining) {
      // draining: refuse new messages (do not commit so a fresh worker re-reads)
      return;
    }
    await this.#pipeline.onMessage(record);
  }

  /** claim → execute → report for one accepted, in-tenant message. */
  async #handleAccepted(msg: DispatchMessage): Promise<void> {
    const cancellation = new SimpleCancellationSignal();
    // fixture 24: mint a FRESH key per distinct write — never a fixed claim-{id}.
    const idemKey = newIdempotencyKey();

    const run = (async (): Promise<void> => {
      try {
        // §1.8 task-params path: SECURITY_REJECTED instead of throwing
        const paramScan = this.#validator.scanTaskParams(msg.parameters);
        if (!paramScan.ok) {
          await this.#report(msg.taskId, {
            success: false,
            errorCode: ErrorCode.SECURITY_REJECTED,
            resultSummary: `credential in parameters: ${paramScan.leakedKeys.join(", ")}`,
          });
          return;
        }

        const claim = await this.#transport.claim(msg.taskId, idemKey);
        // record the ADR-014 invocation token so renew/report can echo it
        const tracked = this.#inFlight.get(msg.taskId);
        if (tracked) tracked.partitionInvocationId = claim.partitionInvocationId ?? null;
        const progress = new NoopProgressReporter();
        const resume = new ResumeSupport({
          taskId: msg.taskId,
          checkpoint: this.#checkpointFactory(msg.taskId),
          progress,
          cancellation,
          options: this.#resumeOptions,
        });
        const ctx: TaskContext = {
          taskId: msg.taskId,
          effectiveConfig: claim.effectiveConfig ?? {},
          traceId:
            claim.traceId ??
            (msg.runtimeAttributes?.traceId as string | undefined) ??
            "",
          cancellation,
          progress,
          checkpoint: () => resume.checkpoint(),
          commit: (breakPosition) => resume.commit(breakPosition),
        };

        let result: TaskResult;
        try {
          result = await this.#handler.execute(ctx);
        } catch (e) {
          // ADR-037 §决策三 — cooperative cancel lands here as a *cancelled*
          // terminal report, not a failure.
          if (e instanceof SdkTaskStopped) {
            result = {
              success: false,
              errorCode: ErrorCode.CANCELLED,
              resultSummary: "task stopped at checkpoint (cancelled)",
              outputs: { breakPosition: e.breakPosition },
            };
          } else {
            result = {
              success: false,
              errorCode: ErrorCode.EXECUTION_FAILED,
              resultSummary: String(e),
            };
          }
        }

        await this.#report(msg.taskId, {
          success: result.success,
          errorCode: result.errorCode,
          outputs: result.outputs,
          resultSummary: result.resultSummary,
          partitionInvocationId:
            this.#inFlight.get(msg.taskId)?.partitionInvocationId ?? null,
        });
      } catch (e) {
        // claim/report fatal — log, then fail-fast on auth errors (§ openapi:
        // 401/403 must stop the worker; a stale/invalid credential won't fix itself).
        this.#logger.error("task pipeline failed", {
          taskId: msg.taskId,
          error: String(e),
        });
        if (e instanceof FatalTransportError && (e.status === 401 || e.status === 403)) {
          this.#logger.error("auth failure (401/403); failing fast", {
            taskId: msg.taskId,
            status: e.status,
          });
          void this.stop(30_000);
        }
      } finally {
        this.#inFlight.delete(msg.taskId);
        // A slot just freed. Route the resume decision through the shared core so
        // the max/2 hysteresis applies: stay paused while in-flight is still in
        // the [max/2, max) band, resume only once it drops below max/2. This
        // avoids pause/resume thrash at the capacity boundary.
        if (this.#consumer.isPaused() && !this.#draining) {
          const bp = decideBackpressure(
            this.#inFlight.size,
            this.#cfg.maxConcurrent,
            true,
          );
          if (bp.action === "backpressure" && bp.kafka === "resume") {
            this.#consumer.resume();
          }
        }
      }
    })();

    this.#inFlight.set(msg.taskId, {
      taskId: msg.taskId,
      cancellation,
      promise: run,
    });
  }

  async #report(taskId: string, body: ReportBody): Promise<void> {
    // fixture 24: fresh key, never a fixed report-{taskId} (a redelivered task's
    // report would otherwise replay the platform's stale first outcome).
    await this.#transport.report(taskId, body, newIdempotencyKey());
  }

  /**
   * stop(timeoutMs): DRAINING → wakeup → drain in-flight (40%) → await executor
   * (60%) → deactivate. Order per wire-protocol §4.
   */
  async stop(timeoutMs = 30_000): Promise<void> {
    if (this.#draining && this.#fsm === "DRAINING" && this.#inFlight.size === 0) {
      // already stopping with nothing in flight — still ensure deactivate ran
    }
    const plan = planStop(timeoutMs);
    this.#draining = true;
    this.#setFsm("DRAINING");

    // wake the consumer (interrupt poll); refuse new messages
    if (plan.kafka === "wakeup") {
      this.#consumer.pause();
      await this.#consumer.wakeup();
    }

    // Keep heartbeat + lease renewal running THROUGH drain: a worker that stops
    // heartbeating during the (up to timeoutMs) drain window can be flagged dead
    // by the platform's missed-heartbeat reaper and have its still-running
    // in-flight tasks redispatched (double-run). Stop both AFTER drain.

    // phase 1: drain in-flight within 40% of the window
    const drainDeadline = Date.now() + timeoutMs * 0.4;
    await this.#awaitInFlight(drainDeadline);

    // phase 2: await executor (remaining tasks' promises) within 60%
    const execDeadline = Date.now() + timeoutMs * 0.6;
    await this.#awaitInFlight(execDeadline);

    this.#heartbeat.stop();
    this.#leaseRenewal.stop();

    // deactivate last (§4)
    if (plan.deactivate) {
      try {
        await this.#transport.deactivate(this.#cfg.workerCode);
        this.#logger.info("deactivated");
      } catch (e) {
        this.#logger.warn("deactivate failed (heartbeat-stop fallback applies)", {
          error: String(e),
        });
      }
    }

    if (this.#sigtermHandler) {
      process.off("SIGTERM", this.#sigtermHandler);
      this.#sigtermHandler = undefined;
    }
  }

  async #awaitInFlight(deadline: number): Promise<void> {
    while (this.#inFlight.size > 0 && Date.now() < deadline) {
      const promises = [...this.#inFlight.values()].map((t) => t.promise);
      const remaining = deadline - Date.now();
      if (remaining <= 0) break;
      await Promise.race([
        Promise.allSettled(promises),
        new Promise((r) => setTimeout(r, Math.min(remaining, 25)).unref?.()),
      ]);
    }
  }
}
