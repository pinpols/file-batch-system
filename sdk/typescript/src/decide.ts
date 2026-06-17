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
    return { action: "not-found", retry: false, failFast: false };
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
    return { action: "client-error", retry: false, failFast: false };
  }
  // 5xx and transport errors (status <= 0 or >= 500) — exponential backoff
  return {
    action: "retry-then-drop",
    retry: true,
    retryBackoffMs: exponentialBackoff(baseMs, maxAttempts),
    maxAttempts,
  };
}

/**
 * Classify a heartbeat / leaseRenew transport failure (wire-protocol §C exemption).
 *
 * Unlike register/claim/report (which run the full exponential-retry sequence),
 * heartbeat and leaseRenew do NOT back off internally on a single failure — they
 * simply skip this tick and try again on the next scheduled tick. So a 503 (or
 * any transport-level failure) yields a single attempt with no retry. (A 4xx like
 * 404 on renew is still classified by classifyHttp → not-found/give-up.)
 */
export function classifyHeartbeatRenewError(): HttpDecision {
  return { action: "retry-then-drop", retry: false, maxAttempts: 1 };
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
  effectiveMaxConcurrent?: number;
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

  // §2.1 dynamic backpressure: platform-suggested concurrency cap. Only a
  // positive value constrains; null/absent leaves local config in effect.
  if (resp.desiredMaxConcurrent != null && resp.desiredMaxConcurrent > 0) {
    result.effectiveMaxConcurrent = resp.desiredMaxConcurrent;
  }

  return result;
}

// ---------------------------------------------------------------------------
// Paused task types (heartbeat directive) — Kafka drop
// ---------------------------------------------------------------------------

export interface PausedTaskDecision {
  action: "apply-directive";
  kafka: KafkaAction;
}

/**
 * Decide what to do with a freshly received Kafka dispatch message whose
 * taskType is in the platform's pausedTaskTypes set (wire-protocol §2.1). The
 * message is dropped WITHOUT committing the offset (platform redelivers after
 * unpausing); a non-paused taskType is processed normally (kafka:none).
 */
export function decidePausedTaskType(
  taskType: string,
  pausedTaskTypes: string[],
): PausedTaskDecision {
  if (pausedTaskTypes.includes(taskType)) {
    return { action: "apply-directive", kafka: "drop-message" };
  }
  return { action: "apply-directive", kafka: "none" };
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
 * concurrency and whether the assignment is already paused.
 *
 * Hysteresis (aligned with Java `KafkaTaskConsumer.applyBackpressure`): pause
 * when in-flight has reached `maxConcurrent`; resume only once in-flight drops
 * BELOW `maxConcurrent / 2` (integer division, floored at 1). Pulling the
 * pause / resume thresholds apart prevents in-flight from thrashing pause/resume
 * in the max-1 / max band, where every resume would trigger a fresh poll burst.
 *
 * - `inFlight >= maxConcurrent`            → backpressure / pause (idempotent if already paused)
 * - `currentlyPaused && inFlight < max/2`  → backpressure / resume
 * - otherwise                              → none (stay paused in the `[max/2, max)` band)
 */
export function decideBackpressure(
  inFlight: number,
  maxConcurrent: number,
  currentlyPaused = false,
): BackpressureDecision {
  if (inFlight >= maxConcurrent) {
    return { action: "backpressure", kafka: "pause", resumeWhenDrained: true };
  }
  if (currentlyPaused && inFlight < resumeThreshold(maxConcurrent)) {
    return { action: "backpressure", kafka: "resume" };
  }
  return { action: "none" };
}

/**
 * Hysteresis low-water mark: in-flight must fall strictly below it to resume.
 * `maxConcurrent / 2` (integer division), floored at 1 so a `maxConcurrent` of 1
 * still has a reachable resume point.
 */
function resumeThreshold(maxConcurrent: number): number {
  return Math.max(1, Math.floor(maxConcurrent / 2));
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

// ---------------------------------------------------------------------------
// Outgoing request construction (request-side conformance)
// ---------------------------------------------------------------------------
//
// These builders model the OUTGOING request body + headers the SDK would send
// for register / claim / renew / report. They mirror the Java wire DTOs
// (RegisterRequest / ClaimRequest / RenewRequest / ReportRequest) and the
// PlatformHttpClient header policy. The conformance runner drives them from a
// fixture's given.config + given.state.request and asserts requestBodyIncludes
// / requestBodyExcludes / requestHeaders. This is what locks the report
// field-name red-line (outputs/success:bool, not output/errorClass/status) and
// the partitionInvocationId pass-through across every language.

export interface OutgoingRequest {
  body: Record<string, unknown>;
  headers: Record<string, string>;
}

/** Boot config the request builders read (subset of the SDK client config). */
export interface RequestBuildConfig {
  tenantId: string;
  workerCode: string;
  apiKey?: string | null;
}

/** Describes which outgoing call to build, from a fixture's given.state.request. */
export interface RequestSpec {
  kind: "register" | "claim" | "renew" | "report";
  taskId?: number;
  /** partitionInvocationId stored at claim time; absent → omitted (NON_NULL). */
  partitionInvocationId?: string | null;
  /** idempotency key for write ops; absent → builder mints one. */
  idempotencyKey?: string;
  /** report payload (success/outputs/errorCode/...) from the fixture. */
  report?: {
    success?: boolean;
    outputs?: Record<string, unknown>;
    errorCode?: string;
    resultSummary?: string;
    failureClass?: string;
  };
}

/** Drop null/undefined values so NON_NULL semantics match the Java DTOs. */
function dropNullish(obj: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(obj)) {
    if (v !== null && v !== undefined) {
      out[k] = v;
    }
  }
  return out;
}

/** Common auth/tenant headers every /internal/* call carries. */
function baseHeaders(config: RequestBuildConfig): Record<string, string> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    "X-Batch-Tenant-Id": config.tenantId,
  };
  if (config.apiKey != null && config.apiKey !== "") {
    headers["X-Batch-Api-Key"] = config.apiKey;
  }
  return headers;
}

/**
 * Build the outgoing request (body + headers) for a register/claim/renew/report
 * call. Field names and NON_NULL omission mirror the platform wire DTOs; apiKey
 * lives only in the header, never the body.
 */
export function buildRequest(
  spec: RequestSpec,
  config: RequestBuildConfig,
): OutgoingRequest {
  const headers = baseHeaders(config);
  switch (spec.kind) {
    case "register": {
      const body = dropNullish({
        tenantId: config.tenantId,
        workerCode: config.workerCode,
        workerGroup: "sdk-self-hosted",
        status: "RUNNING",
      });
      return { body, headers };
    }
    case "claim":
    case "renew": {
      const body = dropNullish({
        tenantId: config.tenantId,
        workerId: config.workerCode,
        partitionInvocationId: spec.partitionInvocationId,
      });
      // write op → per-call idempotency key header
      headers["Idempotency-Key"] =
        spec.idempotencyKey ?? `ts-${randomUuid()}`;
      return { body, headers };
    }
    case "report": {
      const r = spec.report ?? {};
      const body = dropNullish({
        taskId: spec.taskId,
        tenantId: config.tenantId,
        workerId: config.workerCode,
        success: r.success,
        outputs: r.outputs,
        errorCode: r.errorCode,
        resultSummary: r.resultSummary,
        failureClass: r.failureClass,
        partitionInvocationId: spec.partitionInvocationId,
      });
      headers["Idempotency-Key"] =
        spec.idempotencyKey ?? `ts-${randomUuid()}`;
      return { body, headers };
    }
    default:
      throw new Error(`unknown request kind: ${(spec as RequestSpec).kind}`);
  }
}

/** Minimal RFC-4122-ish v4 uuid (no deps); only the shape matters here. */
function randomUuid(): string {
  const hex = (n: number) =>
    Array.from({ length: n }, () =>
      Math.floor(Math.random() * 16).toString(16),
    ).join("");
  return `${hex(8)}-${hex(4)}-4${hex(3)}-${hex(4)}-${hex(12)}`;
}
