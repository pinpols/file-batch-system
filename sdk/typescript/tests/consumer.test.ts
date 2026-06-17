/**
 * Consumer pipeline tests (§1.2 / §1.9 / §A) — schema reject, tenant drop,
 * backpressure, accept.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  MessagePipeline,
  type Assignment,
  type DispatchMessage,
} from "../src/client/consumer.ts";

const silentLogger = { info: () => {}, warn: () => {}, error: () => {} };

function fakeAssignment(): Assignment & { paused: boolean } {
  const a = {
    paused: false,
    pause() {
      this.paused = true;
    },
    resume() {
      this.paused = false;
    },
    isPaused() {
      return this.paused;
    },
  };
  return a;
}

function pipeline(opts: {
  tenantId?: string;
  workerTypes?: readonly string[];
  inFlight?: number;
  maxConcurrent?: number;
  assignment?: Assignment;
  onAccepted?: (m: DispatchMessage) => Promise<void>;
}) {
  return new MessagePipeline({
    tenantId: opts.tenantId ?? "tenant-A",
    workerTypes: opts.workerTypes,
    inFlight: () => opts.inFlight ?? 0,
    maxConcurrent: opts.maxConcurrent ?? 4,
    assignment: opts.assignment ?? fakeAssignment(),
    logger: silentLogger,
    onAccepted: opts.onAccepted ?? (async () => {}),
  });
}

test("consumer: rejects schemaVersion v3 (unknown major), no commit", async () => {
  const p = pipeline({});
  const out = await p.onMessage({
    value: JSON.stringify({ taskId: "t1", tenantId: "tenant-A", schemaVersion: "v3" }),
  });
  assert.equal(out.kind, "rejected-schema");
  assert.equal(out.committed, false);
});

test("consumer: accepts known schemaVersion v2", async () => {
  let accepted: string | undefined;
  const p = pipeline({ onAccepted: async (m) => { accepted = m.taskId; } });
  const out = await p.onMessage({
    value: JSON.stringify({ taskId: "t2", tenantId: "tenant-A", schemaVersion: "v2" }),
  });
  assert.equal(out.kind, "accepted");
  assert.equal(out.committed, true);
  assert.equal(accepted, "t2");
});

test("consumer: drops foreign tenantId (§1.9), no commit", async () => {
  let accepted = false;
  const p = pipeline({
    tenantId: "tenant-A",
    onAccepted: async () => { accepted = true; },
  });
  const out = await p.onMessage({
    value: JSON.stringify({ taskId: "t3", tenantId: "tenant-B" }),
  });
  assert.equal(out.kind, "dropped-tenant");
  assert.equal(out.committed, false);
  assert.equal(accepted, false);
});

test("consumer: drops message whose workerType is not served, no commit", async () => {
  let accepted = false;
  const p = pipeline({
    workerTypes: ["IMPORT"],
    onAccepted: async () => { accepted = true; },
  });
  const out = await p.onMessage({
    value: JSON.stringify({ taskId: "t6", tenantId: "tenant-A", workerType: "EXPORT" }),
  });
  assert.equal(out.kind, "not-for-worker");
  assert.equal(out.committed, false);
  assert.equal(accepted, false);
});

test("consumer: accepts message whose workerType is served", async () => {
  let accepted = false;
  const p = pipeline({
    workerTypes: ["IMPORT", "EXPORT"],
    onAccepted: async () => { accepted = true; },
  });
  const out = await p.onMessage({
    value: JSON.stringify({ taskId: "t7", tenantId: "tenant-A", workerType: "EXPORT" }),
  });
  assert.equal(out.kind, "accepted");
  assert.equal(out.committed, true);
  assert.equal(accepted, true);
});

test("consumer: no workerTypes configured → serves all (no routing filter)", async () => {
  const p = pipeline({});
  const out = await p.onMessage({
    value: JSON.stringify({ taskId: "t8", tenantId: "tenant-A", workerType: "ANYTHING" }),
  });
  assert.equal(out.kind, "accepted");
});

// 回归守护(workerType 跨语言对齐):平台真实 JSON 以 `workerType` 承载路由键;v1 旧名 `taskType`
// 经归一化兼容。用真实 JSON.parse 路径断言路由键绑定 —— 防止 Java 同款"路由键反序列化丢失"在 TS 复现。
test("consumer: v2 workerType JSON binds to routing key on the parsed message", async () => {
  let seen: string | undefined;
  const p = pipeline({
    workerTypes: ["echo"],
    onAccepted: async (m) => {
      seen = m.workerType;
    },
  });
  const out = await p.onMessage({
    value: JSON.stringify({ taskId: "t9", tenantId: "tenant-A", workerType: "echo" }),
  });
  assert.equal(out.kind, "accepted");
  assert.equal(seen, "echo");
});

test("consumer: v1 taskType JSON aliases to workerType (backward compat) and routes", async () => {
  let seen: string | undefined;
  const p = pipeline({
    workerTypes: ["echo"],
    onAccepted: async (m) => {
      seen = m.workerType;
    },
  });
  const out = await p.onMessage({
    // v1 字段名 taskType,无 workerType
    value: JSON.stringify({ taskId: "t10", tenantId: "tenant-A", schemaVersion: "v1", taskType: "echo" }),
  });
  assert.equal(out.kind, "accepted");
  assert.equal(seen, "echo");
});

test("consumer: v1 taskType not served → not-for-worker", async () => {
  const p = pipeline({ workerTypes: ["echo"] });
  const out = await p.onMessage({
    value: JSON.stringify({ taskId: "t11", tenantId: "tenant-A", taskType: "shell" }),
  });
  assert.equal(out.kind, "not-for-worker");
  assert.equal(out.committed, false);
});

test("consumer: backpressure pauses assignment when in-flight at cap", async () => {
  const asn = fakeAssignment();
  const p = pipeline({ inFlight: 4, maxConcurrent: 4, assignment: asn });
  const out = await p.onMessage({
    value: JSON.stringify({ taskId: "t4", tenantId: "tenant-A" }),
  });
  assert.equal(out.kind, "backpressure");
  assert.equal(asn.paused, true);
});

test("consumer: parse error → not committed", async () => {
  const p = pipeline({});
  const out = await p.onMessage({ value: "{not json" });
  assert.equal(out.kind, "parse-error");
  assert.equal(out.committed, false);
});

test("consumer: null/missing schemaVersion → accepted as v1", async () => {
  const p = pipeline({});
  const out = await p.onMessage({
    value: JSON.stringify({ taskId: "t5", tenantId: "tenant-A" }),
  });
  assert.equal(out.kind, "accepted");
});
