/**
 * SensitiveDataValidator (§1.8) — credentials must travel via env, never in a
 * register body or task parameters.
 *
 * Scan an object's keys against SENSITIVE_KEYWORDS; a sensitive key with a
 * non-empty value is a leak. The register path throws (fail-fast at start);
 * the task-params path returns a SECURITY_REJECTED signal so the runtime can
 * report the task failed instead of crashing the worker.
 */

import { SENSITIVE_KEYWORDS } from "../constants.ts";
import { ErrorCode } from "../protocol.ts";

/** Error thrown on the register path when a credential leaks into the body. */
export class SensitiveDataError extends Error {
  readonly leakedKeys: string[];
  constructor(leakedKeys: string[]) {
    super(
      `sensitive data detected in body; credentials must use env not payload: ${leakedKeys.join(
        ", ",
      )}`,
    );
    this.name = "SensitiveDataError";
    this.leakedKeys = leakedKeys;
  }
}

/** Outcome of a task-params scan. */
export interface SensitiveScanResult {
  ok: boolean;
  /** Present only when !ok. */
  errorCode?: ErrorCode;
  leakedKeys: string[];
}

function normalizeKey(key: string): string {
  // case-insensitive, ignore separators so "API-Key" matches "apikey"
  return key.toLowerCase().replace(/[_\-\s]/g, "");
}

export class SensitiveDataValidator {
  #keywords: Set<string>;

  /** @param extraKeywords tenant deny-list extension (§1.8 hook). */
  constructor(extraKeywords: readonly string[] = []) {
    this.#keywords = new Set(
      [...SENSITIVE_KEYWORDS, ...extraKeywords].map(normalizeKey),
    );
  }

  /** Extend the deny-list at runtime (returns this for chaining). */
  addKeyword(keyword: string): this {
    this.#keywords.add(normalizeKey(keyword));
    return this;
  }

  /**
   * Collect keys whose normalized form matches the deny-list AND whose value is
   * a non-empty string. Empty / null / undefined values are allowed (the field
   * may legitimately exist as a placeholder).
   */
  #scan(obj: unknown): string[] {
    const leaked: string[] = [];
    if (obj == null || typeof obj !== "object") return leaked;
    for (const [key, value] of Object.entries(obj as Record<string, unknown>)) {
      const norm = normalizeKey(key);
      const matched = [...this.#keywords].some(
        (kw) => norm === kw || norm.includes(kw),
      );
      if (!matched) continue;
      if (typeof value === "string" && value.length > 0) {
        leaked.push(key);
      } else if (typeof value === "number" || typeof value === "boolean") {
        // a non-string truthy credential is still a leak
        leaked.push(key);
      }
    }
    return leaked;
  }

  /** Register path: throw on any leak (fail-fast at worker start). */
  assertRegisterBody(body: unknown): void {
    const leaked = this.#scan(body);
    if (leaked.length > 0) {
      throw new SensitiveDataError(leaked);
    }
  }

  /** Task-params path: return SECURITY_REJECTED instead of throwing. */
  scanTaskParams(params: unknown): SensitiveScanResult {
    const leaked = this.#scan(params);
    if (leaked.length > 0) {
      return {
        ok: false,
        errorCode: ErrorCode.SECURITY_REJECTED,
        leakedKeys: leaked,
      };
    }
    return { ok: true, leakedKeys: [] };
  }
}
