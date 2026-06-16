/**
 * Cross-language SDK constants mirrored from `docs/api/sdk-shared-constants.yaml`.
 *
 * Authority order (see YAML header):
 *   1. Java enum / static final list (source of truth)
 *   2. sdk-shared-constants.yaml (mirror of #1)
 *   3. This file (consumes the YAML; kept honest by shared-constants-parity.test.ts)
 *
 * Per the conformance contract §1.1 these values are NOT re-authored freely: the
 * parity test deep-equals each array against the YAML and fails on any drift.
 */

/** schema_versions_supported — known major versions the SDK accepts (§A). */
export const SUPPORTED_SCHEMA_VERSIONS: readonly string[] = ["v1", "v2"];

/** worker_runtime_states — worker FSM states. */
export const WORKER_RUNTIME_STATES: readonly string[] = [
  "NORMAL",
  "DEGRADED",
  "PAUSED",
  "DRAINING",
];

/** sensitive_keywords — credential-leak detection keywords. */
export const SENSITIVE_KEYWORDS: readonly string[] = [
  "password",
  "passwd",
  "secret",
  "apikey",
  "api_key",
  "token",
  "credential",
  "accesskey",
  "access_key",
  "privatekey",
  "private_key",
  "clientsecret",
  "client_secret",
];

/** task_statuses — terminal + lifecycle task statuses. */
export const TASK_STATUSES: readonly string[] = [
  "CREATED",
  "READY",
  "RUNNING",
  "SUCCESS",
  "FAILED",
  "CANCELLED",
  "TERMINATED",
];
