/**
 * Scheduler tests — drive tick() directly with a FakeTransport (no real waits).
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  HeartbeatScheduler,
  LeaseRenewalScheduler,
} from "../src/scheduler.ts";
import { FakeTransport } from "../src/testkit.ts";
import { SimpleCancellationSignal } from "../src/handler.ts";
import type { FsmState, KafkaAction } from "../src/protocol.ts";

const silentLogger = {
  info: () => {},
  warn: () => {},
  error: () => {},
};

test("heartbeat: DRAINING directive → FSM DRAINING + consumer paused + onDrain", async () => {
  const transport = new FakeTransport({
    heartbeat: { platformStatus: "DRAINING", shouldDrain: true },
  });

  let fsm: FsmState | undefined;
  const kafka: KafkaAction[] = [];
  let drained = false;

  const hb = new HeartbeatScheduler(
    transport,
    {
      workerCode: "w1",
      buildBody: () => ({ workerCode: "w1" }),
      setFsm: (s) => {
        fsm = s;
      },
      applyKafka: (a) => kafka.push(a),
      onDrain: () => {
        drained = true;
      },
      logger: silentLogger,
    },
    30_000,
  );

  await hb.tick();
  assert.equal(fsm, "DRAINING");
  assert.deepEqual(kafka, ["pause"]);
  assert.equal(drained, true);
});

test("heartbeat: nextHeartbeatHint PT15S → next interval = 15000", async () => {
  const transport = new FakeTransport({
    heartbeat: { platformStatus: "NORMAL", nextHeartbeatHint: "PT15S" },
  });

  const hb = new HeartbeatScheduler(
    transport,
    {
      workerCode: "w1",
      buildBody: () => ({}),
      setFsm: () => {},
      applyKafka: () => {},
      onDrain: () => {},
      logger: silentLogger,
    },
    30_000,
  );

  assert.equal(hb.intervalMs, 30_000, "starts at default 30s");
  const next = await hb.tick();
  assert.equal(next, 15_000, "tick returns the dynamic interval");
  assert.equal(hb.intervalMs, 15_000, "interval re-scheduled to 15s");
});

test("heartbeat: failed tick is swallowed and keeps current interval", async () => {
  const transport = new FakeTransport();
  // make heartbeat throw
  (transport as unknown as { heartbeat: () => Promise<never> }).heartbeat =
    async () => {
      throw new Error("boom");
    };

  const hb = new HeartbeatScheduler(
    transport,
    {
      workerCode: "w1",
      buildBody: () => ({}),
      setFsm: () => assert.fail("should not transition on failure"),
      applyKafka: () => {},
      onDrain: () => {},
      logger: silentLogger,
    },
    30_000,
  );

  const next = await hb.tick();
  assert.equal(next, 30_000);
});

test("leaseRenewal: cancelRequested=true → cancellation signal flips", async () => {
  const transport = new FakeTransport({ renew: { cancelRequested: true } });
  const sig = new SimpleCancellationSignal();

  const lr = new LeaseRenewalScheduler(
    transport,
    {
      inFlight: () => [{ taskId: "task-1", cancellation: sig }],
      buildBody: (taskId) => ({ taskId }),
      dropTask: () => assert.fail("should not drop on a normal renew"),
      logger: silentLogger,
    },
    60_000,
  );

  await lr.tick();
  assert.equal(sig.isCancellationRequested, true);
  assert.equal(transport.countOf("renew"), 1);
});

test("leaseRenewal: renew throwing (lease gone) → task dropped locally", async () => {
  const transport = new FakeTransport();
  (transport as unknown as { renew: () => Promise<never> }).renew = async () => {
    throw new Error("404 lease gone");
  };
  const sig = new SimpleCancellationSignal();
  let dropped: string | undefined;

  const lr = new LeaseRenewalScheduler(
    transport,
    {
      inFlight: () => [{ taskId: "task-1", cancellation: sig }],
      buildBody: (taskId) => ({ taskId }),
      dropTask: (id) => {
        dropped = id;
      },
      logger: silentLogger,
    },
    60_000,
  );

  await lr.tick();
  assert.equal(dropped, "task-1");
});
