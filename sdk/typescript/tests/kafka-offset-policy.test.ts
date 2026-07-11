/**
 * offsetPolicy — the pure Kafka offset rules (no kafkajs dependency).
 *
 *   - "commit" advances PAST the record (offset + 1, kafkajs commit semantics).
 *   - "withhold" / "backpressure" seek BACK to the record's own offset so a later
 *     commit can never cross it (§A/§1.9, parity with Go/Java seek+pause).
 *   - fromBeginning defaults to false (latest), parity with the other 4 SDKs; the
 *     old default of true replayed the whole topic on every new group.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  offsetAction,
  resolveFromBeginning,
} from "../src/kafka/offsetPolicy.ts";

test("offsetAction: commit → commit offset+1 (advance past)", () => {
  const a = offsetAction("commit", "topic-a", 3, "41");
  assert.deepEqual(a, { type: "commit", topic: "topic-a", partition: 3, offset: "42" });
});

test("offsetAction: withhold → seek back to the SAME offset + pause (never cross)", () => {
  const a = offsetAction("withhold", "topic-a", 3, "41");
  assert.deepEqual(a, {
    type: "seek-pause",
    topic: "topic-a",
    partition: 3,
    offset: "41", // NOT 42 — a later commit must never cross this offset
    reason: "withhold",
  });
});

test("offsetAction: backpressure → seek back to the SAME offset + pause", () => {
  const a = offsetAction("backpressure", "topic-b", 0, "7");
  assert.deepEqual(a, {
    type: "seek-pause",
    topic: "topic-b",
    partition: 0,
    offset: "7",
    reason: "backpressure",
  });
});

test("offsetAction: commit handles large (64-bit) offsets without precision loss", () => {
  const a = offsetAction("commit", "t", 0, "9007199254740993"); // > Number.MAX_SAFE_INTEGER
  assert.equal(a.type, "commit");
  assert.equal(a.offset, "9007199254740994");
});

test("resolveFromBeginning: defaults to false (latest), parity with the 4 other SDKs", () => {
  assert.equal(resolveFromBeginning(undefined), false);
});

test("resolveFromBeginning: an explicit value always wins", () => {
  assert.equal(resolveFromBeginning(true), true);
  assert.equal(resolveFromBeginning(false), false);
});
