/**
 * SDK-side request signing (scheme A, opt-in) — HMAC over a canonical string
 * keyed by the tenant's api_key, with a timestamp + nonce to defend against
 * replay. Must be BYTE-FOR-BYTE identical to the server
 * (`io.github.pinpols.batch.common.security.RequestSignatures`) and the Java
 * SDK signer (`...sdk.internal.RequestSigner`) — they are pinned together by
 * the conformance golden vectors:
 *
 * ```
 *   canonical = UPPER(method) "\n" path "\n" timestamp "\n" nonce "\n" hex(sha256(body))
 *   signature = hex(hmacSha256(apiKey, canonical))     // lowercase hex
 * ```
 *
 * The server defaults the check OFF (`batch.request-signing.enabled=false`);
 * the SDK only attaches the headers when explicitly opted in AND an api_key is
 * present (no key → nothing to sign with).
 */

import { createHash, createHmac, randomUUID } from "node:crypto";

/** Header names — must match the server-side filter (case-insensitive on the wire). */
export const SIGNATURE_HEADERS = {
  timestamp: "X-Batch-Timestamp",
  nonce: "X-Batch-Nonce",
  signature: "X-Batch-Signature",
} as const;

/** hex(sha256(body)) — empty body still hashes (sha256 of zero bytes). */
export function bodySha256Hex(body: string | Uint8Array): string {
  return createHash("sha256").update(body ?? "").digest("hex");
}

/** The canonical string the HMAC is computed over. */
export function canonicalString(
  method: string,
  path: string,
  timestamp: string,
  nonce: string,
  body: string | Uint8Array,
): string {
  return (
    (method ?? "").toUpperCase() +
    "\n" +
    (path ?? "") +
    "\n" +
    (timestamp ?? "") +
    "\n" +
    (nonce ?? "") +
    "\n" +
    bodySha256Hex(body)
  );
}

/** hex(hmacSha256(apiKey, canonical)) — lowercase hex. */
export function sign(
  apiKey: string,
  method: string,
  path: string,
  timestamp: string,
  nonce: string,
  body: string | Uint8Array,
): string {
  return createHmac("sha256", apiKey ?? "")
    .update(canonicalString(method, path, timestamp, nonce, body))
    .digest("hex");
}

/**
 * Build the three signature headers for a write request. `timestamp` is epoch
 * MILLISECONDS as a string; `nonce` is a fresh UUID. Injectable clock/nonce so
 * callers (and tests) can pin them.
 */
export function signatureHeaders(
  apiKey: string,
  method: string,
  path: string,
  body: string | Uint8Array,
  now: () => number = Date.now,
  nonceGen: () => string = randomUUID,
): Record<string, string> {
  const timestamp = String(now());
  const nonce = nonceGen();
  return {
    [SIGNATURE_HEADERS.timestamp]: timestamp,
    [SIGNATURE_HEADERS.nonce]: nonce,
    [SIGNATURE_HEADERS.signature]: sign(apiKey, method, path, timestamp, nonce, body),
  };
}
