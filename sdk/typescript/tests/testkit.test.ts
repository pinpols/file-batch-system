/**
 * testkit ergonomics — reportFor() assertion sugar + withFakePlatform() wrapper.
 *
 * These mirror the Java testkit's awaitReport + @BatchWorkerTest-injected
 * platform, so a tenant test does not hand-scan transport.calls or re-wire a
 * FakePlatform in every case.
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import {
  FakePlatform,
  reportFor,
  withFakePlatform,
} from "../src/client/testkit.ts";

test("reportFor returns the last recorded report body for a taskId", async () => {
  const platform = new FakePlatform();
  await platform.transport.report("42", { success: true, resultSummary: "ok" }, "idem-1");
  await platform.transport.report("99", { success: false, resultSummary: "other" }, "idem-2");

  const r = reportFor(platform.transport, "42");
  assert.ok(r, "expected a report for task 42");
  assert.equal(r.success, true);
  assert.equal(r.resultSummary, "ok");

  assert.equal(reportFor(platform.transport, "absent"), undefined);
});

test("reportFor returns the latest when a task is reported twice (last wins)", async () => {
  const platform = new FakePlatform();
  await platform.transport.report("7", { success: false, resultSummary: "first" }, "k1");
  await platform.transport.report("7", { success: true, resultSummary: "second" }, "k2");
  assert.equal(reportFor(platform.transport, "7")?.resultSummary, "second");
});

test("withFakePlatform builds a platform, feeds messages, and returns body result", async () => {
  const seen = await withFakePlatform(
    async (platform) => {
      // messages were pre-fed into the consumer; draining delivers them
      const delivered: string[] = [];
      await platform.consumer.start(async (r) => {
        delivered.push(r.value);
      });
      assert.equal(delivered.length, 1);
      return "done";
    },
    { messages: [{ schemaVersion: "v1", taskId: 42, tenantId: "t1" }] },
  );
  assert.equal(seen, "done");
});

test("withFakePlatform applies the transport script", async () => {
  await withFakePlatform(
    async (platform) => {
      const ack = await platform.transport.register({});
      assert.equal(ack.idempotent, true);
    },
    { script: { register: { idempotent: true } } },
  );
});
