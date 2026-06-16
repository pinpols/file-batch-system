/**
 * FakePlatform — scripts transport responses, feeds consumer records, and
 * records every call so tests can verify the runtime without a real broker or
 * HTTP server.
 */

import type {
  ClaimResponse,
  RegisterAck,
  ReportBody,
  Transport,
} from "./transport.ts";
import { FatalTransportError, NotFoundTransportError } from "./transport.ts";
import type { HeartbeatResponse, RenewResponse } from "./protocol.ts";
import { FakeConsumer, type ConsumerRecord } from "./consumer.ts";

/** Recorded call for assertions. */
export interface RecordedCall {
  op: string;
  args: unknown[];
}

type Responder<T> = T | (() => T) | (() => Promise<T>);

async function resolveResponder<T>(r: Responder<T>): Promise<T> {
  if (typeof r === "function") {
    return await (r as () => T | Promise<T>)();
  }
  return r;
}

export interface FakeTransportScript {
  register?: Responder<RegisterAck>;
  heartbeat?: Responder<HeartbeatResponse> | Array<Responder<HeartbeatResponse>>;
  deactivate?: Responder<void>;
  claim?: Responder<ClaimResponse>;
  report?: Responder<void>;
  renew?: Responder<RenewResponse> | Array<Responder<RenewResponse>>;
}

/**
 * In-memory Transport. Each method returns the scripted response (or a sane
 * default) and records the call. Array scripts are consumed per-call so a test
 * can script "DRAINING on the first beat, NORMAL after".
 */
export class FakeTransport implements Transport {
  readonly calls: RecordedCall[] = [];
  #script: FakeTransportScript;
  #hbIdx = 0;
  #renewIdx = 0;

  constructor(script: FakeTransportScript = {}) {
    this.#script = script;
  }

  /** count calls to one op. */
  countOf(op: string): number {
    return this.calls.filter((c) => c.op === op).length;
  }

  #record(op: string, ...args: unknown[]): void {
    this.calls.push({ op, args });
  }

  async register(body: Record<string, unknown>): Promise<RegisterAck> {
    this.#record("register", body);
    return resolveResponder(this.#script.register ?? { idempotent: false });
  }

  async heartbeat(
    workerCode: string,
    body: Record<string, unknown>,
  ): Promise<HeartbeatResponse> {
    this.#record("heartbeat", workerCode, body);
    const s = this.#script.heartbeat;
    if (Array.isArray(s)) {
      const item = s[Math.min(this.#hbIdx, s.length - 1)];
      this.#hbIdx += 1;
      return resolveResponder(item);
    }
    return resolveResponder(s ?? {});
  }

  async deactivate(workerCode: string): Promise<void> {
    this.#record("deactivate", workerCode);
    await resolveResponder(this.#script.deactivate ?? undefined);
  }

  async claim(taskId: string, idempotencyKey: string): Promise<ClaimResponse> {
    this.#record("claim", taskId, idempotencyKey);
    return resolveResponder(
      this.#script.claim ?? { effectiveConfig: {}, leaseUntil: null },
    );
  }

  async report(
    taskId: string,
    body: ReportBody,
    idempotencyKey: string,
  ): Promise<void> {
    this.#record("report", taskId, body, idempotencyKey);
    await resolveResponder(this.#script.report ?? undefined);
  }

  async renew(
    taskId: string,
    body: Record<string, unknown>,
  ): Promise<RenewResponse> {
    this.#record("renew", taskId, body);
    const s = this.#script.renew;
    if (Array.isArray(s)) {
      const item = s[Math.min(this.#renewIdx, s.length - 1)];
      this.#renewIdx += 1;
      return resolveResponder(item);
    }
    return resolveResponder(s ?? {});
  }
}

/** Helper: build a Transport whose claim/report throw a Fatal (401) error. */
export function fatalClaimTransport(status = 401): Transport {
  const base = new FakeTransport();
  const t: Transport = {
    register: (b) => base.register(b),
    heartbeat: (w, b) => base.heartbeat(w, b),
    deactivate: (w) => base.deactivate(w),
    claim: async () => {
      throw new FatalTransportError("claim fatal", status);
    },
    report: (id, b, k) => base.report(id, b, k),
    renew: (id, b) => base.renew(id, b),
  };
  return t;
}

export { NotFoundTransportError };

/** Bundles a FakeTransport + FakeConsumer for an end-to-end runtime test. */
export class FakePlatform {
  readonly transport: FakeTransport;
  readonly consumer: FakeConsumer;

  constructor(
    script: FakeTransportScript = {},
    records: ConsumerRecord[] = [],
  ) {
    this.transport = new FakeTransport(script);
    this.consumer = new FakeConsumer(records);
  }

  /** Feed JSON dispatch messages (serialized) to the consumer. */
  feedMessages(...messages: unknown[]): void {
    this.consumer.feed(
      ...messages.map((m) => ({ value: JSON.stringify(m) })),
    );
  }
}
