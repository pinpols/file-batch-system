/**
 * Pure decision core for the BYO worker SDK.
 *
 * Each function maps a protocol input (HTTP status, heartbeat directive, renew
 * response, capacity / stop signal) to a structured result whose fields match
 * the closed `then.expect` vocabulary in the conformance fixtures. No IO here:
 * real HTTP/Kafka wrappers call these and act on the result.
 *
 * Authoritative rules: wire-protocol.md §A (schemaVersion), §B (error codes),
 * §C (retry / backoff); byo-conformance-contract.md §2 (field semantics).
 */

import { SUPPORTED_SCHEMA_VERSIONS } from "./constants.ts";
import type {
  DecisionAction,
  FsmState,
  HeartbeatResponse,
  KafkaAction,
  RenewResponse,
} from "./protocol.ts";

/** Default retry tuning (wire-protocol §C: base=200ms, maxAttempts=3). */
export const DEFAULT_RETRY_BASE_MS = 200;
export const DEFAULT_RETRY_MAX_ATTEMPTS = 3;
/**累计 4xx fail-fast 阈值 (clientErrorFailFastThreshold). */
export const CLIENT_ERROR_FAIL_FAST_THRESHOLD = 5;
/** nextHeartbeatHint sanity clamp lower bound (must not drift below 1s). */
export const MIN_HEARTBEAT_INTERVAL_MS = 1000;

// ---------------------------------------------------------------------------
// §B / §C — HTTP status classification
// ---------------------------------------------------------------------------

export interface HttpDecision {
  action: DecisionAction | "success" | "not-found" | "client-error";
  retry: boolean;
  failFast?: boolean;
  idempotent?: boolean;
  reportFailure?: boolean;
  retryBackoffMs?: number[];
  maxAttempts?: number;
}

/**
 * Compute the exponential backoff sleep sequence: base * 2^(n-1) for
 * n = 1..maxAttempts. base=200, maxAttempts=3 → [200, 400, 800].
 */
export function exponentialBackoff(
  baseMs: number = DEFAULT_RETRY_BASE_MS,
  maxAttempts: number = DEFAULT_RETRY_MAX_ATTEMPTS,
): number[] {
  const seq: number[] = [];
  for (let n = 1; n <= maxAttempts; n++) {
    seq.push(baseMs * 2 ** (n - 1));
  }
  return seq;
}

/**
 * Classify an HTTP response from a control-plane call (wire-protocol §B/§C).
 *
 * @param status            HTTP status code (or 0 / negative for transport error)
 * @param clientErrorCount  running count of prior non-auth 4xx errors (this call
 *                          increments it; fail-fast once it reaches the threshold)
 */
export function classifyHttp(
  status: number,
  clientErrorCount = 0,
  baseMs: number = DEFAULT_RETRY_BASE_MS,
  maxAttempts: number = DEFAULT_RETRY_MAX_ATTEMPTS,
): HttpDecision {
  // 2xx — success
  if (status >= 200 && status < 300) {
    return { action: "success", retry: false };
  }
  // 401 / 403 — auth: fail-fast, never retry
  if (status === 401 || status === 403) {
    return { action: "fail-fast", failFast: true, retry: false };
  }
  // 404 — give up this request, caller decides
  if (status === 404) {
    return { action: "not-found", retry: false };
  }
  // 409 — idempotent success (already claimed / lease reclaimed)
  if (status === 409) {
    return {
      action: "idempotent-success",
      retry: false,
      idempotent: true,
      reportFailure: false,
    };
  }
  // other 4xx — count toward fail-fast threshold
  if (status >= 400 && status < 500) {
    const nextCount = clientErrorCount + 1;
    if (nextCount >= CLIENT_ERROR_FAIL_FAST_THRESHOLD) {
      return { action: "fail-fast", failFast: true, retry: false };
    }
    return { action: "client-error", retry: false };
  }
  // 5xx and transport errors (status <= 0 or >= 500) — exponential backoff
  return {
    action: "retry-then-drop",
    retry: true,
    retryBackoffMs: exponentialBackoff(baseMs, maxAttempts),
    maxAttempts,
  };
}

// ---------------------------------------------------------------------------
// §A — schemaVersion classification
// ---------------------------------------------------------------------------

/**
 * Decide whether a dispatch message's schemaVersion is processable (§A).
 * null/undefined → treated as v1 (accept); known major in SUPPORTED → accept;
 * unknown major (v3+) → reject (do not commit offset).
 */
export function classifySchemaVersion(
  version: string | null | undefined,
): "accept" | "reject" {
  if (version == null || version === "") {
    return "accept"; // legacy orchestrator without the field → v1
  }
  // major = leading "v<digits>" prefix; tolerate suffixes like "v2-rc"
  const match = /^v\d+/.exec(version);
  const major = match ? match[0] : version;
  return SUPPORTED_SCHEMA_VERSIONS.includes(major) ? "accept" : "reject";
}

// ---------------------------------------------------------------------------
// Heartbeat directive
// ---------------------------------------------------------------------------

export interface HeartbeatDecision {
  action: "apply-directive";
  fsmTransition?: FsmState;
  kafka?: KafkaAction;
  drainThenDeactivate?: boolean;
  heartbeatNextIntervalMs?: number;
}

/** Parse an ISO-8601 duration ("PT15S", "PT1M30S", "PT30S") to milliseconds. */
export function parseIso8601DurationMs(iso: string): number {
  const m = /^PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?$/.exec(
    iso,
  );
  if (!m || (m[1] == null && m[2] == null && m[3] == null)) {
    throw new Error(`invalid ISO-8601 duration: ${iso}`);
  }
  const hours = m[1] ? parseFloat(m[1]) : 0;
  const minutes = m[2] ? parseFloat(m[2]) : 0;
  const seconds = m[3] ? parseFloat(m[3]) : 0;
  return Math.round((hours * 3600 + minutes * 60 + seconds) * 1000);
}

/** Normalize a nextHeartbeatHint (ISO duration string or raw seconds) to ms. */
function hintToMs(hint: string | number): number {
  const ms = typeof hint === "number" ? hint * 1000 : parseIso8601DurationMs(hint);
  // sanity clamp: interval must not drift below 1s
  return Math.max(ms, MIN_HEARTBEAT_INTERVAL_MS);
}

/**
 * Apply a heartbeat reverse-directive (wire-protocol §2.1).
 * Precedence: shouldDrain/DRAINING > PAUSED > NORMAL. nextHeartbeatHint is
 * orthogonal and applied whenever present.
 */
export function applyHeartbeatDirective(
  resp: HeartbeatResponse,
): HeartbeatDecision {
  const result: HeartbeatDecision = { action: "apply-directive" };

  if (resp.shouldDrain === true || resp.platformStatus === "DRAINING") {
    result.fsmTransition = "DRAINING";
    result.kafka = "pause";
    result.drainThenDeactivate = true;
  } else if (resp.platformStatus === "PAUSED") {
    result.fsmTransition = "PAUSED";
    result.kafka = "pause";
  } else if (resp.platformStatus === "NORMAL") {
    result.fsmTransition = "NORMAL";
    result.kafka = "none";
  } else if (resp.platformStatus === "DEGRADED") {
    result.fsmTransition = "DEGRADED";
    result.kafka = "none";
  }

  if (resp.nextHeartbeatHint != null) {
    result.heartbeatNextIntervalMs = hintToMs(resp.nextHeartbeatHint);
  }

  return result;
}

// ---------------------------------------------------------------------------
// Lease renew
// ---------------------------------------------------------------------------

export interface RenewDecision {
  action: "cancel" | "none";
  cancelRequested?: boolean;
}

/** Apply a lease-renew response (wire-protocol §2.2). */
export function applyRenew(resp: RenewResponse): RenewDecision {
  if (resp.cancelRequested === true) {
    return { action: "cancel", cancelRequested: true };
  }
  return { action: "none" };
}

// ---------------------------------------------------------------------------
// Capacity backpressure
// ---------------------------------------------------------------------------

export interface BackpressureDecision {
  action: "backpressure" | "none";
  kafka?: KafkaAction;
  resumeWhenDrained?: boolean;
}

/**
 * Decide Kafka backpressure on a freshly received message given current
 * concurrency. When in-flight has reached maxConcurrent, pause the assignment
 * and resume once one slot drains.
 */
export function decideBackpressure(
  inFlight: number,
  maxConcurrent: number,
): BackpressureDecision {
  if (inFlight >= maxConcurrent) {
    return { action: "backpressure", kafka: "pause", resumeWhenDrained: true };
  }
  return { action: "none" };
}

// ---------------------------------------------------------------------------
// Graceful stop
// ---------------------------------------------------------------------------

export interface StopDecision {
  action: "graceful-stop";
  kafka: KafkaAction;
  deactivate: boolean;
  drainThenDeactivate: boolean;
  withinMs: number;
}

/** Build the graceful-stop plan (wire-protocol §4 stop sequence). */
export function planStop(stopTimeoutMs: number): StopDecision {
  return {
    action: "graceful-stop",
    kafka: "wakeup",
    deactivate: true,
    drainThenDeactivate: true,
    withinMs: stopTimeoutMs,
  };
}

// ---------------------------------------------------------------------------
// Register
// ---------------------------------------------------------------------------

export interface RegisterDecision {
  action: "register-online";
  fsmTransition: FsmState;
  startSchedulers: string[];
  kafka: KafkaAction;
  idempotent?: boolean;
}

/**
 * Decide the register success path (wire-protocol §4). 200 is success whether
 * the platform created a fresh record or idempotently reused an existing one;
 * pass `idempotent=true` when the (tenant, workerCode) already existed.
 */
export function decideRegister(idempotent = false): RegisterDecision {
  const result: RegisterDecision = {
    action: "register-online",
    fsmTransition: "NORMAL",
    startSchedulers: ["heartbeat", "leaseRenew"],
    kafka: "subscribe",
  };
  if (idempotent) {
    result.idempotent = true;
  }
  return result;
}
