/**
 * Request-signing conformance (scheme A, opt-in). The golden vectors below are
 * the cross-language contract: identical bytes must be produced by the server
 * (`RequestSignatures`) and the Java SDK (`RequestSigner`). Any drift in the
 * canonical string / HMAC turns these red.
 *
 * Also asserts the HttpTransport wiring: signature headers appear on write
 * requests only when opted in AND an api_key is present.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import {
  bodySha256Hex,
  sign,
  signatureHeaders,
  SIGNATURE_HEADERS,
} from "../src/client/signing.ts";
import { HttpTransport } from "../src/client/transport.ts";

// --- golden vectors (byte-for-byte contract) --------------------------------

const GOLDEN = {
  apiKey: "golden-key",
  method: "POST",
  path: "/internal/tasks/42/report",
  timestamp: "1700000000000",
  nonce: "golden-nonce",
  body: '{"tenantId":"t1","success":true}',
};

test("signing golden: bodySha256Hex(body) matches the contract vector", () => {
  assert.equal(
    bodySha256Hex(GOLDEN.body),
    "c9a04b2061b2c381193ee868b9d89bc16979c738d257f8495d18457a83462dd5",
  );
});

test("signing golden: sign(...) matches the contract vector", () => {
  assert.equal(
    sign(
      GOLDEN.apiKey,
      GOLDEN.method,
      GOLDEN.path,
      GOLDEN.timestamp,
      GOLDEN.nonce,
      GOLDEN.body,
    ),
    "287108832407aec1bc689c97ac22037b7114b2702671dfb20d1aacc6edeb0898",
  );
});

test("signing golden: empty body sha256 matches the contract vector", () => {
  assert.equal(
    bodySha256Hex(""),
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  );
});

test("signing: method is upper-cased in the canonical string", () => {
  assert.equal(
    sign(GOLDEN.apiKey, "post", GOLDEN.path, GOLDEN.timestamp, GOLDEN.nonce, GOLDEN.body),
    sign(GOLDEN.apiKey, "POST", GOLDEN.path, GOLDEN.timestamp, GOLDEN.nonce, GOLDEN.body),
  );
});

test("signing: signatureHeaders emits the three headers with a pinned clock+nonce", () => {
  const headers = signatureHeaders(
    GOLDEN.apiKey,
    GOLDEN.method,
    GOLDEN.path,
    GOLDEN.body,
    () => 1700000000000,
    () => "golden-nonce",
  );
  assert.equal(headers[SIGNATURE_HEADERS.timestamp], "1700000000000");
  assert.equal(headers[SIGNATURE_HEADERS.nonce], "golden-nonce");
  assert.equal(
    headers[SIGNATURE_HEADERS.signature],
    "287108832407aec1bc689c97ac22037b7114b2702671dfb20d1aacc6edeb0898",
  );
});

// --- transport wiring -------------------------------------------------------

function startServer(): Promise<{
  url: string;
  close: () => Promise<void>;
  last: () => http.IncomingHttpHeaders;
}> {
  let lastHeaders: http.IncomingHttpHeaders = {};
  return new Promise((resolve) => {
    const server = http.createServer((req, res) => {
      lastHeaders = req.headers;
      const chunks: Buffer[] = [];
      req.on("data", (c: Buffer) => chunks.push(c));
      req.on("end", () => {
        res.statusCode = 200;
        res.end("{}");
      });
    });
    server.listen(0, "127.0.0.1", () => {
      const addr = server.address();
      const port = typeof addr === "object" && addr ? addr.port : 0;
      resolve({
        url: `http://127.0.0.1:${port}`,
        close: () => new Promise((r) => server.close(() => r(undefined))),
        last: () => lastHeaders,
      });
    });
  });
}

test("transport: signing enabled + apiKey → write request carries signature headers", async () => {
  const srv = await startServer();
  try {
    const t = new HttpTransport({
      baseUrl: srv.url,
      tenantId: "t1",
      workerCode: "w1",
      apiKey: "golden-key",
      requestSigningEnabled: true,
      now: () => 1700000000000,
      nonceGen: () => "golden-nonce",
    });
    await t.report("42", { success: true }, "idem-1");
    t.close();
    const h = srv.last();
    assert.equal(h["x-batch-api-key"], "golden-key");
    assert.equal(h["x-batch-timestamp"], "1700000000000");
    assert.equal(h["x-batch-nonce"], "golden-nonce");
    // signature over the actual wire path + payload bytes (verified to be 64 hex chars)
    assert.match(String(h["x-batch-signature"]), /^[0-9a-f]{64}$/);
  } finally {
    await srv.close();
  }
});

test("transport: signing disabled → no signature headers, but api-key still sent", async () => {
  const srv = await startServer();
  try {
    const t = new HttpTransport({
      baseUrl: srv.url,
      tenantId: "t1",
      workerCode: "w1",
      apiKey: "golden-key",
      // requestSigningEnabled defaults to false
    });
    await t.report("42", { success: true }, "idem-1");
    t.close();
    const h = srv.last();
    assert.equal(h["x-batch-api-key"], "golden-key");
    assert.equal(h["x-batch-timestamp"], undefined);
    assert.equal(h["x-batch-nonce"], undefined);
    assert.equal(h["x-batch-signature"], undefined);
  } finally {
    await srv.close();
  }
});

test("transport: signing enabled but no apiKey → nothing signed", async () => {
  const srv = await startServer();
  try {
    const t = new HttpTransport({
      baseUrl: srv.url,
      tenantId: "t1",
      workerCode: "w1",
      requestSigningEnabled: true,
    });
    await t.report("42", { success: true }, "idem-1");
    t.close();
    const h = srv.last();
    assert.equal(h["x-batch-api-key"], undefined);
    assert.equal(h["x-batch-signature"], undefined);
  } finally {
    await srv.close();
  }
});
