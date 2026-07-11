/**
 * HttpTransport — TLS selection (P0: http-only import sent api_key in cleartext
 * even for https:// base URLs) and the CONSECUTIVE 4xx fail-fast counter (P1: the
 * old "running" count meant a long-lived worker turned any 4xx fatal after 5
 * unrelated 4xx over its whole lifetime).
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { HttpTransport, FatalTransportError } from "../src/client/transport.ts";

function startServer(
  handler: (req: http.IncomingMessage, res: http.ServerResponse) => void,
): Promise<{ url: string; port: number; close: () => Promise<void> }> {
  return new Promise((resolve) => {
    const server = http.createServer(handler);
    server.listen(0, "127.0.0.1", () => {
      const addr = server.address();
      const port = typeof addr === "object" && addr ? addr.port : 0;
      resolve({
        url: `http://127.0.0.1:${port}`,
        port,
        close: () => new Promise((r) => server.close(() => r(undefined))),
      });
    });
  });
}

// --- TLS scheme selection --------------------------------------------------

test("transport: https:// base_url is marked secure (selects node:https)", () => {
  const t = new HttpTransport({
    baseUrl: "https://api.example.com:8443",
    tenantId: "t",
    workerCode: "w",
    warn: () => {},
  });
  assert.equal(t.isSecure, true);
  (t as unknown as { close(): void }).close();
});

test("transport: http:// over a NON-loopback host warns (api_key would be cleartext)", () => {
  const warnings: string[] = [];
  const t = new HttpTransport({
    baseUrl: "http://api.example.com",
    tenantId: "t",
    workerCode: "w",
    warn: (m) => warnings.push(m),
  });
  assert.equal(t.isSecure, false);
  assert.equal(warnings.length, 1, "one cleartext warning");
  assert.match(warnings[0], /CLEARTEXT/);
  (t as unknown as { close(): void }).close();
});

test("transport: http:// over loopback does NOT warn (test/dev is fine)", () => {
  const warnings: string[] = [];
  for (const host of ["127.0.0.1", "localhost"]) {
    const t = new HttpTransport({
      baseUrl: `http://${host}:18080`,
      tenantId: "t",
      workerCode: "w",
      warn: (m) => warnings.push(m),
    });
    assert.equal(t.isSecure, false);
    (t as unknown as { close(): void }).close();
  }
  assert.equal(warnings.length, 0, "loopback http must not warn");
});

test("transport: an https:// client will NOT silently speak plain http to an http server", async () => {
  // Point an https transport at a plain-http server: the TLS handshake fails, so
  // the request errors (transport error → retried → exhausted). This proves the
  // https URL is actually driving node:https, not being sent as cleartext http.
  const srv = await startServer((_req, res) => {
    res.statusCode = 200;
    res.end("{}");
  });
  const t = new HttpTransport({
    baseUrl: srv.url.replace("http://", "https://"),
    tenantId: "t",
    workerCode: "w",
    retryBaseMs: 1,
    retryMaxAttempts: 2,
    sleep: async () => {},
    warn: () => {},
  });
  await assert.rejects(() => t.claim("task-1", "idem-1"));
  (t as unknown as { close(): void }).close();
  await srv.close();
});

// --- consecutive 4xx fail-fast --------------------------------------------

test("transport: 4xx counter is CONSECUTIVE — a success resets it (no false fatal)", async () => {
  // Server flips between 400 and 200 on demand.
  let mode: 400 | 200 = 400;
  const srv = await startServer((_req, res) => {
    res.statusCode = mode;
    res.end(mode === 200 ? "{}" : "bad");
  });
  const t = new HttpTransport({
    baseUrl: srv.url,
    tenantId: "t",
    workerCode: "w",
    sleep: async () => {},
  });

  // 4 consecutive 400s: each throws client-error (NOT fatal fail-fast) and the
  // count climbs to 4 (threshold is 5).
  for (let i = 1; i <= 4; i++) {
    mode = 400;
    await assert.rejects(() => t.claim("task", "k"));
    assert.equal(t.clientErrorCount, i, `count after ${i} consecutive 4xx`);
  }

  // a single success RESETS the counter to 0.
  mode = 200;
  await t.claim("task", "k");
  assert.equal(t.clientErrorCount, 0, "success resets consecutive 4xx count");

  // so the next 4xx is client-error #1 again — the worker never false-fatals.
  mode = 400;
  await assert.rejects(() => t.claim("task", "k"));
  assert.equal(t.clientErrorCount, 1, "counter resumed from 0 after the reset");

  (t as unknown as { close(): void }).close();
  await srv.close();
});

test("transport: 5 CONSECUTIVE 4xx trips fail-fast (threshold)", async () => {
  const srv = await startServer((_req, res) => {
    res.statusCode = 400;
    res.end("bad");
  });
  const t = new HttpTransport({
    baseUrl: srv.url,
    tenantId: "t",
    workerCode: "w",
    sleep: async () => {},
  });

  // calls 1-4 push the count to 4 (client-error each).
  for (let i = 0; i < 4; i++) await assert.rejects(() => t.claim("task", "k"));
  assert.equal(t.clientErrorCount, 4);

  // call 5: nextCount reaches the threshold → fail-fast. The count does NOT
  // increment further (fail-fast branch), so it stays at 4.
  await assert.rejects(
    () => t.claim("task", "k"),
    (e: unknown) => e instanceof FatalTransportError,
  );
  assert.equal(t.clientErrorCount, 4, "fail-fast branch does not bump the count");

  (t as unknown as { close(): void }).close();
  await srv.close();
});
