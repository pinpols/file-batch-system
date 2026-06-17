/**
 * HeartbeatScheduler + LeaseRenewalScheduler (§1.3 / §1.4).
 *
 * Both take an INJECTABLE interval and an INJECTABLE transport so tests drive
 * ticks without real 30s/60s waits (drive `tick()` directly). They call the
 * transport, route the response through the phase-1 decision functions
 * (applyHeartbeatDirective / applyRenew), update the FSM via the supplied
 * callbacks, and signal cancellation.
 *
 * Java short板 we fix here: the HeartbeatScheduler DYNAMICALLY re-schedules its
 * next interval from `nextHeartbeatHint` (Java接而不用).
 */

import { applyHeartbeatDirective, applyRenew } from "../decide.ts";
import type { FsmState, KafkaAction } from "../protocol.ts";
import type { Transport } from "./transport.ts";
import {
  NotFoundTransportError,
  RevokedTransportError,
} from "./transport.ts";
import type { CancellationSignal } from "./handler.ts";
import type { Logger } from "./consumer.ts";
import { consoleLogger } from "./consumer.ts";

/** Hooks the heartbeat scheduler uses to apply a directive to the worker. */
export interface HeartbeatHooks {
  workerCode: string;
  /** build the heartbeat request body (capabilityTags, buildId, sdkVersion...). */
  buildBody: () => Record<string, unknown>;
  /** apply an FSM transition decided by applyHeartbeatDirective. */
  setFsm: (state: FsmState) => void;
  /** apply a Kafka action (pause/resume/none) to the consumer assignment. */
  applyKafka: (action: KafkaAction) => void;
  /** platform asked us to drain then deactivate. */
  onDrain: () => void;
  /** platform desiredMaxConcurrent (optional). */
  setMaxConcurrent?: (n: number) => void;
  logger?: Logger;
}

/**
 * Periodic heartbeat with dynamic re-scheduling from nextHeartbeatHint.
 *
 * Lifecycle: `start()` arms a setTimeout chain; `tick()` performs one beat and
 * returns the next interval (tests call `tick()` directly). `stop()` cancels.
 */
export class HeartbeatScheduler {
  #transport: Transport;
  #hooks: HeartbeatHooks;
  #intervalMs: number;
  #logger: Logger;
  #timer: NodeJS.Timeout | undefined;
  #running = false;

  constructor(
    transport: Transport,
    hooks: HeartbeatHooks,
    intervalMs = 30_000,
  ) {
    this.#transport = transport;
    this.#hooks = hooks;
    this.#intervalMs = intervalMs;
    this.#logger = hooks.logger ?? consoleLogger;
  }

  /** Current (possibly dynamically-adjusted) interval. */
  get intervalMs(): number {
    return this.#intervalMs;
  }

  /**
   * Perform one heartbeat: call transport, apply directive, return the next
   * interval (and store it for the timer chain). Single failures are swallowed
   * (§5: periodic tick, next tick retries) so the loop never wedges.
   */
  async tick(): Promise<number> {
    try {
      const resp = await this.#transport.heartbeat(
        this.#hooks.workerCode,
        this.#hooks.buildBody(),
      );
      const decision = applyHeartbeatDirective(resp);

      if (decision.fsmTransition) {
        this.#hooks.setFsm(decision.fsmTransition);
      }
      if (decision.kafka && decision.kafka !== "none") {
        this.#hooks.applyKafka(decision.kafka);
      }
      if (decision.drainThenDeactivate) {
        this.#hooks.onDrain();
      }
      if (
        resp.desiredMaxConcurrent != null &&
        this.#hooks.setMaxConcurrent
      ) {
        this.#hooks.setMaxConcurrent(resp.desiredMaxConcurrent);
      }
      // dynamic speed control — the Java short板 fix
      if (decision.heartbeatNextIntervalMs != null) {
        this.#intervalMs = decision.heartbeatNextIntervalMs;
      }
    } catch (e) {
      this.#logger.warn("heartbeat tick failed; will retry next tick", {
        error: String(e),
      });
    }
    return this.#intervalMs;
  }

  start(): void {
    if (this.#running) return;
    this.#running = true;
    const loop = async (): Promise<void> => {
      if (!this.#running) return;
      const next = await this.tick();
      if (!this.#running) return;
      this.#timer = setTimeout(() => void loop(), next);
      // do not keep the event loop alive solely for the heartbeat timer
      this.#timer.unref?.();
    };
    // first beat after one interval (fixedDelay semantics, §4)
    this.#timer = setTimeout(() => void loop(), this.#intervalMs);
    this.#timer.unref?.();
  }

  stop(): void {
    this.#running = false;
    if (this.#timer) {
      clearTimeout(this.#timer);
      this.#timer = undefined;
    }
  }
}

/** One in-flight task tracked for lease renewal. */
export interface InFlightTask {
  taskId: string;
  cancellation: CancellationSignal;
}

export interface LeaseRenewalHooks {
  /** live snapshot of in-flight tasks to renew. */
  inFlight: () => InFlightTask[];
  /** build the renew request body (TaskHeartbeatRequest). */
  buildBody: (taskId: string) => Record<string, unknown>;
  /** drop a task locally when its lease is gone (404/409). */
  dropTask: (taskId: string) => void;
  logger?: Logger;
}

/**
 * Periodic lease renewal: iterate in-flight tasks, renew each, flip
 * cancellation when the platform sets cancelRequested, drop on lost lease.
 */
export class LeaseRenewalScheduler {
  #transport: Transport;
  #hooks: LeaseRenewalHooks;
  #intervalMs: number;
  #logger: Logger;
  #timer: NodeJS.Timeout | undefined;
  #running = false;

  constructor(
    transport: Transport,
    hooks: LeaseRenewalHooks,
    intervalMs = 60_000,
  ) {
    this.#transport = transport;
    this.#hooks = hooks;
    this.#intervalMs = intervalMs;
    this.#logger = hooks.logger ?? consoleLogger;
  }

  get intervalMs(): number {
    return this.#intervalMs;
  }

  /** Renew every in-flight task once. */
  async tick(): Promise<void> {
    const tasks = this.#hooks.inFlight();
    for (const task of tasks) {
      try {
        const resp = await this.#transport.renew(
          task.taskId,
          this.#hooks.buildBody(task.taskId),
        );
        const decision = applyRenew(resp);
        if (decision.action === "cancel") {
          task.cancellation.markCancelled();
          this.#logger.info("platform cancelled task; signalled handler", {
            taskId: task.taskId,
          });
        }
      } catch (e) {
        // Only a definitive lease-gone (404 NotFound / 409 Revoked) drops the
        // task + cancels the handler. A transient error (5xx / network / auth)
        // must NOT drop — that would stop renewing a still-running task, let the
        // lease expire, and cause a double-run. Keep it and retry next tick.
        if (
          e instanceof NotFoundTransportError ||
          e instanceof RevokedTransportError
        ) {
          this.#logger.warn("renew: lease gone; cancelling handler + dropping", {
            taskId: task.taskId,
            error: String(e),
          });
          task.cancellation.markCancelled();
          this.#hooks.dropTask(task.taskId);
        } else {
          this.#logger.warn("renew failed transiently; retrying next tick", {
            taskId: task.taskId,
            error: String(e),
          });
        }
      }
    }
  }

  start(): void {
    if (this.#running) return;
    this.#running = true;
    const loop = async (): Promise<void> => {
      if (!this.#running) return;
      await this.tick();
      if (!this.#running) return;
      this.#timer = setTimeout(() => void loop(), this.#intervalMs);
      this.#timer.unref?.();
    };
    this.#timer = setTimeout(() => void loop(), this.#intervalMs);
    this.#timer.unref?.();
  }

  stop(): void {
    this.#running = false;
    if (this.#timer) {
      clearTimeout(this.#timer);
      this.#timer = undefined;
    }
  }
}
