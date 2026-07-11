/**
 * HttpTransport.claim — partitionInvocationId travels IN the claim body (parity
 * with Go/Python/Rust/Java, which all send it; the TS SDK previously relied on a
 * response echo only), and a 409 surfaces as `idempotent` so the caller skips
 * execution instead of double-running.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { HttpTransport } from "../src/client/transport.ts";

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
        close: () => new Promise((r) => server.close(() => r(undefined))),
      });
    });
  });
}

function captureBody(
  onBody: (b: string) => void,
  status = 200,
  payload = "{}",
) {
  return (req: http.IncomingMessage, res: http.ServerResponse) => {
    const chunks: Buffer[] = [];
    req.on("data", (c: Buffer) => chunks.push(c));
    req.on("end", () => {
      onBody(Buffer.concat(chunks).toString("utf8"));
      res.statusCode = status;
      res.end(payload);
    });
  };
}

test("claim: carries partitionInvocationId in the body when provided", async () => {
  let body = "";
  const srv = await startServer(captureBody((b) => (body = b)));
  const t = new HttpTransport({ baseUrl: srv.url, tenantId: "t", workerCode: "w", sleep: async () => {} });

  await t.claim("task-9", "k", "inv-77");
  assert.deepEqual(JSON.parse(body), {
    tenantId: "t",
    workerId: "w",
    partitionInvocationId: "inv-77",
  });

  (t as unknown as { close(): void }).close();
  await srv.close();
});

test("claim: OMITS partitionInvocationId (NON_NULL) when absent or empty", async () => {
  for (const inv of [undefined, null, ""] as const) {
    let body = "";
    const srv = await startServer(captureBody((b) => (body = b)));
    const t = new HttpTransport({ baseUrl: srv.url, tenantId: "t", workerCode: "w", sleep: async () => {} });
    await t.claim("task-9", "k", inv);
    assert.deepEqual(JSON.parse(body), { tenantId: "t", workerId: "w" }, `inv=${String(inv)}`);
    (t as unknown as { close(): void }).close();
    await srv.close();
  }
});

test("claim: 409 → ClaimResponse.idempotent = true (caller must skip execution)", async () => {
  const srv = await startServer((_req, res) => {
    res.statusCode = 409;
    res.end("already claimed");
  });
  const t = new HttpTransport({ baseUrl: srv.url, tenantId: "t", workerCode: "w", sleep: async () => {} });

  const claim = await t.claim("task-9", "k", "inv-1");
  assert.equal(claim.idempotent, true);

  (t as unknown as { close(): void }).close();
  await srv.close();
});

test("claim: 200 → idempotent is falsy (normal claim proceeds)", async () => {
  const srv = await startServer((_req, res) => {
    res.statusCode = 200;
    res.end(JSON.stringify({ effectiveConfig: { a: 1 } }));
  });
  const t = new HttpTransport({ baseUrl: srv.url, tenantId: "t", workerCode: "w", sleep: async () => {} });

  const claim = await t.claim("task-9", "k");
  assert.ok(!claim.idempotent, "200 claim is not idempotent-already-claimed");
  assert.deepEqual(claim.effectiveConfig, { a: 1 });

  (t as unknown as { close(): void }).close();
  await srv.close();
});
