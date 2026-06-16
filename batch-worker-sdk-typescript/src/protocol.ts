/**
 * Wire-protocol types for the orchestrator <-> SDK control plane.
 * Mirrors `docs/sdk/wire-protocol.md` §2 (reverse directives) and §B (error codes).
 */

/** Platform status carried on the heartbeat response (wire-protocol §2.1). */
export type PlatformStatus = "NORMAL" | "DEGRADED" | "PAUSED" | "DRAINING";

/** Worker FSM target states. */
export type FsmState = "NORMAL" | "DEGRADED" | "PAUSED" | "DRAINING";

/** Kafka consumer actions the decision core can request. */
export type KafkaAction =
  | "none"
  | "subscribe"
  | "pause"
  | "resume"
  | "wakeup"
  | "drop-message";

/** Primary decision actions (closed set, mirrors fixture `then.expect.action`). */
export type DecisionAction =
  | "register-online"
  | "apply-directive"
  | "fail-fast"
  | "idempotent-success"
  | "retry-then-drop"
  | "cancel"
  | "backpressure"
  | "graceful-stop";

/** Heartbeat reverse-directive response body (wire-protocol §2.1). */
export interface HeartbeatResponse {
  platformStatus?: PlatformStatus | null;
  shouldDrain?: boolean | null;
  desiredMaxConcurrent?: number | null;
  pausedTaskTypes?: string[] | null;
  /** ISO-8601 duration string ("PT15S") or a raw seconds number, or null. */
  nextHeartbeatHint?: string | number | null;
}

/** Lease renew response body (wire-protocol §2.2). */
export interface RenewResponse {
  leaseUntil?: string | null;
  cancelRequested?: boolean | null;
}

/**
 * Canonical platform error codes (wire-protocol §B report errorCode).
 *
 * Modelled as a frozen const object (not a TS `enum`) so the source runs under
 * Node's strip-only `--experimental-strip-types` (enums need full transpile).
 * Public shape is unchanged: `ErrorCode.SUCCESS` etc. still works.
 */
export const ErrorCode = {
  SUCCESS: "SUCCESS",
  TIMEOUT: "TIMEOUT",
  CANCELLED: "CANCELLED",
  KILLED: "KILLED",
  SECURITY_REJECTED: "SECURITY_REJECTED",
  EXECUTION_FAILED: "EXECUTION_FAILED",
  CONFIG_INVALID: "CONFIG_INVALID",
  RESOURCE_EXHAUSTED: "RESOURCE_EXHAUSTED",
} as const;
export type ErrorCode = (typeof ErrorCode)[keyof typeof ErrorCode];

/** ADR-012 failure classes (const object; see ErrorCode note). */
export const FailureClass = {
  TRANSIENT: "TRANSIENT",
  TERMINAL_USER: "TERMINAL_USER",
  TERMINAL_CONFIG: "TERMINAL_CONFIG",
  BUSINESS: "BUSINESS",
} as const;
export type FailureClass = (typeof FailureClass)[keyof typeof FailureClass];
