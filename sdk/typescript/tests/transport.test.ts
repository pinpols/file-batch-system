/**
 * HttpTransport tests against a real node:http server (no broker, no network
 * beyond loopback). Verifies retry+backoff on 503→200, Fatal on 401, and 409
 * idempotent-success.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { HttpTransport, FatalTransportError } from "../src/client/transport.ts";

function startServer(
  handler: (req: http.IncomingMessage, res: http.ServerResponse) => void,
): Promise<{ url: string; close: () => Promise<void> }> {
  return new Promise((resolve) => {
    const server = http.createServer(handler);
    server.listen(0, "127.0.0.1", () => {
      const addr = server.address();
      const port = typeof addr === "object" && addr ? addr.port : 0;
      resolve({
        url: `http://127.0.0.1:${port}`,
        close: () =>
          new Promise((r) => server.close(() => r(undefined))),
      });
    });
  });
}

test("transport: 503 then 200 → retry with backoff, eventual success", async () => {
  let hits = 0;
  const srv = await startServer((req, res) => {
    hits += 1;
    if (hits === 1) {
      res.statusCode = 503;
      res.end("busy");
    } else {
      res.statusCode = 200;
      res.end(JSON.stringify({ effectiveConfig: { foo: "bar" } }));
    }
  });

  const slept: number[] = [];
  const transport = new HttpTransport({
    baseUrl: srv.url,
    retryBaseMs: 5,
    retryMaxAttempts: 3,
    sleep: async (ms) => {
      slept.push(ms);
    },
  });

  const claim = await transport.claim("t1", "idem-1");
  assert.equal(hits, 2, "should have retried once after the 503");
  assert.deepEqual(slept, [5], "first backoff slot used");
  assert.deepEqual(claim.effectiveConfig, { foo: "bar" });

  (transport as unknown as { close(): void }).close();
  await srv.close();
});

test("transport: 401 → FatalTransportError, no retry", async () => {
  let hits = 0;
  const srv = await startServer((req, res) => {
    hits += 1;
    res.statusCode = 401;
    res.end("nope");
  });

  const transport = new HttpTransport({
    baseUrl: srv.url,
    sleep: async () => {},
  });

  await assert.rejects(
    () => transport.claim("t1", "idem-1"),
    (e: unknown) =>
      e instanceof FatalTransportError && (e as FatalTransportError).status === 401,
  );
  assert.equal(hits, 1, "401 must not retry");

  (transport as unknown as { close(): void }).close();
  await srv.close();
});

test("transport: 409 → idempotent success (register idempotent=true)", async () => {
  const srv = await startServer((req, res) => {
    res.statusCode = 409;
    res.end("already");
  });

  const transport = new HttpTransport({ baseUrl: srv.url, sleep: async () => {} });
  const ack = await transport.register({ workerCode: "w1" });
  assert.equal(ack.idempotent, true);

  (transport as unknown as { close(): void }).close();
  await srv.close();
});

test("transport: sets Idempotency-Key on claim/report", async () => {
  let claimKey: string | undefined;
  const srv = await startServer((req, res) => {
    claimKey = req.headers["idempotency-key"] as string | undefined;
    res.statusCode = 200;
    res.end("{}");
  });

  const transport = new HttpTransport({ baseUrl: srv.url, sleep: async () => {} });
  await transport.claim("task-9", "my-idem-key");
  assert.equal(claimKey, "my-idem-key");

  (transport as unknown as { close(): void }).close();
  await srv.close();
});

test("transport: request timeout aborts a hung request (treated as transport error → retry)", async () => {
  let hits = 0;
  const srv = await startServer((req, res) => {
    hits += 1;
    if (hits === 1) {
      // never respond → trigger timeout
      return;
    }
    res.statusCode = 200;
    res.end("{}");
  });

  const slept: number[] = [];
  const transport = new HttpTransport({
    baseUrl: srv.url,
    timeoutMs: 50,
    retryBaseMs: 1,
    retryMaxAttempts: 3,
    sleep: async (ms) => {
      slept.push(ms);
    },
  });

  await transport.claim("t1", "idem");
  assert.equal(hits, 2, "timed-out request retried");
  assert.ok(slept.length >= 1, "backoff slept after timeout");

  (transport as unknown as { close(): void }).close();
  await srv.close();
});
