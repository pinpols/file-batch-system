/**
 * Transport layer for the 8 control-plane calls (§1.1).
 *
 * `Transport` is the seam the lifecycle / schedulers depend on; tests inject a
 * fake. `HttpTransport` is the production impl over `node:http` with:
 *   - per-request timeout (default 10s, §4 Node pit: net/http has NO default
 *     timeout, so an unbounded socket would hang the heartbeat loop). We arm
 *     both an AbortController and a socket timeout.
 *   - keep-alive agent (§1.1: every reconnect lets heartbeats pile up).
 *   - Idempotency-Key header on claim / report (at-least-once → 409 dedup).
 *
 * Every call routes the HTTP status through the phase-1 `classifyHttp` and
 * acts on the decision: 5xx / transport → retry on the backoff sequence with
 * real setTimeout sleeps; 401/403 → throw FatalTransportError; 409 → treated as
 * idempotent success; other 4xx → fail after the fail-fast threshold.
 */

import http from "node:http";
import {
  classifyHttp,
  DEFAULT_RETRY_BASE_MS,
  DEFAULT_RETRY_MAX_ATTEMPTS,
  type HttpDecision,
} from "../decide.ts";
import type {
  HeartbeatResponse,
  RenewResponse,
} from "../protocol.ts";

/** Effective task config returned by claim. */
export interface ClaimResponse {
  effectiveConfig?: Record<string, unknown>;
  leaseUntil?: string | null;
  traceId?: string | null;
}

/** Register acknowledgement. */
export interface RegisterAck {
  idempotent: boolean;
}

/** Report request body — field names are the §B red line. */
export interface ReportBody {
  success: boolean;
  errorCode?: string;
  outputs?: Record<string, unknown>;
  resultSummary?: string;
}

/** The 8 control-plane operations (§1.1 stable surface). */
export interface Transport {
  register(body: Record<string, unknown>): Promise<RegisterAck>;
  heartbeat(workerCode: string, body: Record<string, unknown>): Promise<HeartbeatResponse>;
  deactivate(workerCode: string): Promise<void>;
  claim(taskId: string, idempotencyKey: string): Promise<ClaimResponse>;
  report(taskId: string, body: ReportBody, idempotencyKey: string): Promise<void>;
  renew(taskId: string, body: Record<string, unknown>): Promise<RenewResponse>;
}

/** Thrown on 401/403 (and exhausted-retry/4xx fail-fast) — caller must die. */
export class FatalTransportError extends Error {
  readonly status: number;
  constructor(message: string, status: number) {
    super(message);
    this.name = "FatalTransportError";
    this.status = status;
  }
}

/** A not-found (404) outcome the caller may treat as "give up this request". */
export class NotFoundTransportError extends Error {
  readonly status = 404;
  constructor(message: string) {
    super(message);
    this.name = "NotFoundTransportError";
  }
}

export interface HttpTransportOptions {
  baseUrl: string; // e.g. "http://127.0.0.1:18080"
  timeoutMs?: number; // default 10000 (§5)
  retryBaseMs?: number;
  retryMaxAttempts?: number;
  /** injectable sleep so tests can avoid real backoff waits */
  sleep?: (ms: number) => Promise<void>;
  /** static headers, e.g. auth token from env */
  headers?: Record<string, string>;
}

interface RawResponse {
  status: number; // 0 → transport error
  body: string;
}

const realSleep = (ms: number): Promise<void> =>
  new Promise((resolve) => setTimeout(resolve, ms));

export class HttpTransport implements Transport {
  #baseUrl: URL;
  #timeoutMs: number;
  #retryBaseMs: number;
  #retryMaxAttempts: number;
  #sleep: (ms: number) => Promise<void>;
  #headers: Record<string, string>;
  #agent: http.Agent;
  /** running count of non-auth 4xx for the fail-fast threshold (§B). */
  #clientErrorCount = 0;

  constructor(opts: HttpTransportOptions) {
    this.#baseUrl = new URL(opts.baseUrl);
    this.#timeoutMs = opts.timeoutMs ?? 10_000;
    this.#retryBaseMs = opts.retryBaseMs ?? DEFAULT_RETRY_BASE_MS;
    this.#retryMaxAttempts = opts.retryMaxAttempts ?? DEFAULT_RETRY_MAX_ATTEMPTS;
    this.#sleep = opts.sleep ?? realSleep;
    this.#headers = opts.headers ?? {};
    // keep-alive agent — §1.1: avoid per-call TCP+TLS handshake.
    this.#agent = new http.Agent({ keepAlive: true, maxSockets: 16 });
  }

  /** Tear down pooled sockets (call on worker stop). */
  close(): void {
    this.#agent.destroy();
  }

  // --- low-level single HTTP request with timeout -------------------------

  #request(
    path: string,
    body: unknown,
    extraHeaders: Record<string, string> = {},
  ): Promise<RawResponse> {
    return new Promise<RawResponse>((resolve) => {
      const url = new URL(path, this.#baseUrl);
      const payload = body === undefined ? "" : JSON.stringify(body);
      const ac = new AbortController();
      const req = http.request(
        url,
        {
          method: "POST",
          agent: this.#agent,
          signal: ac.signal,
          headers: {
            "content-type": "application/json",
            "content-length": Buffer.byteLength(payload),
            ...this.#headers,
            ...extraHeaders,
          },
        },
        (res) => {
          const chunks: Buffer[] = [];
          res.on("data", (c: Buffer) => chunks.push(c));
          res.on("end", () => {
            clearTimeout(timer);
            resolve({
              status: res.statusCode ?? 0,
              body: Buffer.concat(chunks).toString("utf8"),
            });
          });
        },
      );

      // §4 Node pit: arm BOTH a hard timeout (AbortController) and the socket
      // timeout, so a stalled connect or a slow body both fail fast.
      const timer = setTimeout(() => {
        ac.abort();
        req.destroy();
      }, this.#timeoutMs);

      req.setTimeout(this.#timeoutMs, () => {
        ac.abort();
        req.destroy();
      });

      req.on("error", () => {
        clearTimeout(timer);
        resolve({ status: 0, body: "" }); // transport error → classify as retry
      });

      if (payload) req.write(payload);
      req.end();
    });
  }

  /**
   * Drive a control-plane call: retry on 5xx/transport per the backoff
   * sequence, throw Fatal on 401/403, surface 409 as idempotent-success, and
   * fail-fast on the cumulative 4xx threshold.
   *
   * @param retryable whether this op should walk the backoff sequence; heartbeat
   *   / renew are periodic ticks and pass `false` (§5: single failure waits for
   *   the next tick, no internal backoff).
   */
  async #call(
    op: string,
    path: string,
    body: unknown,
    headers: Record<string, string>,
    retryable: boolean,
  ): Promise<RawResponse> {
    let lastDecision: HttpDecision | undefined;
    // attempts = 1 (initial) + backoff slots for retryable ops
    const backoff = retryable
      ? this.#retryBaseMsSeq()
      : [];
    let attempt = 0;
    // total tries = 1 + backoff.length
    for (;;) {
      const res = await this.#request(path, body, headers);
      const decision = classifyHttp(
        res.status,
        this.#clientErrorCount,
        this.#retryBaseMs,
        this.#retryMaxAttempts,
      );
      lastDecision = decision;

      switch (decision.action) {
        case "success":
        case "idempotent-success":
          return res;
        case "fail-fast":
          throw new FatalTransportError(
            `${op} failed fatally (status=${res.status})`,
            res.status,
          );
        case "not-found":
          throw new NotFoundTransportError(`${op} not found (404)`);
        case "client-error":
          this.#clientErrorCount += 1;
          throw new FatalTransportError(
            `${op} client error (status=${res.status})`,
            res.status,
          );
        case "retry-then-drop": {
          if (attempt >= backoff.length) {
            throw new FatalTransportError(
              `${op} exhausted retries (status=${res.status})`,
              res.status,
            );
          }
          await this.#sleep(backoff[attempt]);
          attempt += 1;
          continue;
        }
        default:
          return res;
      }
    }
  }

  #retryBaseMsSeq(): number[] {
    const seq: number[] = [];
    for (let n = 1; n <= this.#retryMaxAttempts; n++) {
      seq.push(this.#retryBaseMs * 2 ** (n - 1));
    }
    return seq;
  }

  static #parse<T>(raw: RawResponse): T {
    if (!raw.body) return {} as T;
    try {
      return JSON.parse(raw.body) as T;
    } catch {
      return {} as T;
    }
  }

  // --- the 8 operations ---------------------------------------------------

  async register(body: Record<string, unknown>): Promise<RegisterAck> {
    const raw = await this.#call(
      "register",
      "/internal/workers/register",
      body,
      {},
      true,
    );
    // 409 → idempotent reuse of an existing (tenant, workerCode)
    return { idempotent: raw.status === 409 };
  }

  async heartbeat(
    workerCode: string,
    body: Record<string, unknown>,
  ): Promise<HeartbeatResponse> {
    const raw = await this.#call(
      "heartbeat",
      `/internal/workers/${encodeURIComponent(workerCode)}/heartbeat`,
      body,
      {},
      false, // periodic tick: no internal backoff (§5)
    );
    return HttpTransport.#parse<HeartbeatResponse>(raw);
  }

  async deactivate(workerCode: string): Promise<void> {
    await this.#call(
      "deactivate",
      `/internal/workers/${encodeURIComponent(workerCode)}/deactivate`,
      {},
      {},
      true,
    );
  }

  async claim(taskId: string, idempotencyKey: string): Promise<ClaimResponse> {
    const raw = await this.#call(
      "claim",
      `/internal/tasks/${encodeURIComponent(taskId)}/claim`,
      {},
      { "idempotency-key": idempotencyKey },
      true,
    );
    return HttpTransport.#parse<ClaimResponse>(raw);
  }

  async report(
    taskId: string,
    body: ReportBody,
    idempotencyKey: string,
  ): Promise<void> {
    await this.#call(
      "report",
      `/internal/tasks/${encodeURIComponent(taskId)}/report`,
      body,
      { "idempotency-key": idempotencyKey },
      true,
    );
  }

  async renew(
    taskId: string,
    body: Record<string, unknown>,
  ): Promise<RenewResponse> {
    const raw = await this.#call(
      "renew",
      `/internal/tasks/${encodeURIComponent(taskId)}/renew`,
      body,
      {},
      false, // periodic tick: no internal backoff (§5)
    );
    return HttpTransport.#parse<RenewResponse>(raw);
  }
}
