/**
 * dispositionOf — maps every PipelineOutcome kind to the Kafka offset policy the
 * production start() path must apply (commit / withhold / backpressure). This is
 * the seam that was missing: start() fed records to the pipeline but never turned
 * the outcome into a commit/pause action, so offsets never advanced.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { dispositionOf, type PipelineOutcome } from "../src/client/consumer.ts";

const msg = { taskId: "t", tenantId: "x", workerType: "IMPORT" };

test("dispositionOf: accepted → commit (advance past)", () => {
  const o: PipelineOutcome = { kind: "accepted", message: msg, committed: true };
  assert.equal(dispositionOf(o), "commit");
});

test("dispositionOf: parse-error → commit (poison commit-skip, §4.5)", () => {
  const o: PipelineOutcome = { kind: "parse-error", committed: true };
  assert.equal(dispositionOf(o), "commit");
});

test("dispositionOf: backpressure → backpressure (seek back + pause)", () => {
  const o: PipelineOutcome = { kind: "backpressure", message: msg, committed: false };
  assert.equal(dispositionOf(o), "backpressure");
});

test("dispositionOf: rejected-schema → withhold (never commit / never cross)", () => {
  const o: PipelineOutcome = { kind: "rejected-schema", committed: false };
  assert.equal(dispositionOf(o), "withhold");
});

test("dispositionOf: dropped-tenant → withhold (§1.9)", () => {
  const o: PipelineOutcome = { kind: "dropped-tenant", committed: false };
  assert.equal(dispositionOf(o), "withhold");
});

test("dispositionOf: not-for-worker → withhold", () => {
  const o: PipelineOutcome = { kind: "not-for-worker", committed: false };
  assert.equal(dispositionOf(o), "withhold");
});
