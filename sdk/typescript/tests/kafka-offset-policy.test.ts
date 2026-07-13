/**
 * offsetPolicy — the pure Kafka offset rules (no kafkajs dependency).
 *
 *   - "commit" advances PAST the record (offset + 1, kafkajs commit semantics).
 *   - "withhold" records the record's own offset as the partition commit CEILING
 *     and KEEPS consuming; no later commit may cross it (§A/§1.9, mirrors the Go
 *     SDK's `committable` ceiling; Java/Python/Rust use the same invariant).
 *   - "backpressure" seeks BACK to the record's own offset + pauses (temporary).
 *   - fromBeginning defaults to false (latest), parity with the other 4 SDKs; the
 *     old default of true replayed the whole topic on every new group.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  offsetAction,
  resolveFromBeginning,
  loweredCeiling,
  commitBlockedByWithheld,
} from "../src/kafka/offsetPolicy.ts";

test("offsetAction: commit → commit offset+1 (advance past)", () => {
  const a = offsetAction("commit", "topic-a", 3, "41");
  assert.deepEqual(a, { type: "commit", topic: "topic-a", partition: 3, offset: "42" });
});

test("offsetAction: withhold → ceiling at the SAME offset (keep consuming, never cross)", () => {
  const a = offsetAction("withhold", "topic-a", 3, "41");
  assert.deepEqual(a, {
    type: "withhold",
    topic: "topic-a",
    partition: 3,
    offset: "41", // NOT 42 — a later commit must never cross this offset
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

test("loweredCeiling: first withhold sets the ceiling to the record offset", () => {
  assert.equal(loweredCeiling(undefined, "5"), "5");
});

test("loweredCeiling: keeps the LOWEST offset ever withheld on the partition", () => {
  assert.equal(loweredCeiling("5", "8"), "5"); // later, higher offset does not raise it
  assert.equal(loweredCeiling("5", "3"), "3"); // an earlier offset lowers it
  assert.equal(loweredCeiling("9007199254740993", "9007199254740992"), "9007199254740992");
});

test("commitBlockedByWithheld: no ceiling → never blocked", () => {
  assert.equal(commitBlockedByWithheld(undefined, "100"), false);
});

test("commitBlockedByWithheld: the withheld offset itself and anything past it are blocked", () => {
  assert.equal(commitBlockedByWithheld("5", "5"), true); // the withheld record
  assert.equal(commitBlockedByWithheld("5", "6"), true); // a later record cannot cross it
  assert.equal(commitBlockedByWithheld("5", "4"), false); // an earlier record still commits
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
