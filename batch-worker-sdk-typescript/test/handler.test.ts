/**
 * Handler SPI tests — cancellation flips, TaskResult field names (§B red line).
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  SimpleCancellationSignal,
  taskSuccess,
  taskFailure,
} from "../src/handler.ts";
import { ErrorCode } from "../src/protocol.ts";

test("handler: cancellation signal flips and fires onCancel once", () => {
  const sig = new SimpleCancellationSignal();
  let fired = 0;
  sig.onCancel(() => { fired += 1; });
  assert.equal(sig.isCancellationRequested, false);

  sig.markCancelled();
  assert.equal(sig.isCancellationRequested, true);
  assert.equal(fired, 1);

  // idempotent: a second markCancelled does not re-fire
  sig.markCancelled();
  assert.equal(fired, 1);

  // onCancel registered after cancellation fires immediately
  let late = 0;
  sig.onCancel(() => { late += 1; });
  assert.equal(late, 1);
});

test("handler: taskSuccess uses exact field names outputs/resultSummary", () => {
  const r = taskSuccess({ rows: 10 }, "done");
  assert.equal(r.success, true);
  assert.deepEqual(r.outputs, { rows: 10 });
  assert.equal(r.resultSummary, "done");
  assert.ok(!("errorCode" in r));
  // no forbidden aliases
  assert.ok(!("output" in r));
  assert.ok(!("errorMessage" in r));
});

test("handler: taskFailure uses errorCode (not errorClass)", () => {
  const r = taskFailure(ErrorCode.CONFIG_INVALID, "missing field");
  assert.equal(r.success, false);
  assert.equal(r.errorCode, "CONFIG_INVALID");
  assert.equal(r.resultSummary, "missing field");
  assert.ok(!("errorClass" in r));
});

test("handler: taskFailure default errorCode = EXECUTION_FAILED", () => {
  const r = taskFailure();
  assert.equal(r.errorCode, ErrorCode.EXECUTION_FAILED);
});
