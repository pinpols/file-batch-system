/**
 * SensitiveDataValidator (§1.8) tests.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  SensitiveDataValidator,
  SensitiveDataError,
} from "../src/client/sensitive.ts";
import { ErrorCode } from "../src/protocol.ts";

test("sensitive: register body with {password:'x'} throws", () => {
  const v = new SensitiveDataValidator();
  assert.throws(
    () => v.assertRegisterBody({ password: "x", workerCode: "w1" }),
    (e: unknown) => e instanceof SensitiveDataError,
  );
});

test("sensitive: register body with {password:''} is allowed (placeholder)", () => {
  const v = new SensitiveDataValidator();
  assert.doesNotThrow(() =>
    v.assertRegisterBody({ password: "", workerCode: "w1" }),
  );
});

test("sensitive: task params with token → SECURITY_REJECTED, not throw", () => {
  const v = new SensitiveDataValidator();
  const res = v.scanTaskParams({ token: "abc123", region: "us" });
  assert.equal(res.ok, false);
  assert.equal(res.errorCode, ErrorCode.SECURITY_REJECTED);
  assert.deepEqual(res.leakedKeys, ["token"]);
});

test("sensitive: clean task params → ok", () => {
  const v = new SensitiveDataValidator();
  const res = v.scanTaskParams({ region: "us", count: 5 });
  assert.equal(res.ok, true);
  assert.deepEqual(res.leakedKeys, []);
});

test("sensitive: case/separator insensitive (API_KEY, Access-Key)", () => {
  const v = new SensitiveDataValidator();
  assert.equal(v.scanTaskParams({ API_KEY: "k" }).ok, false);
  assert.equal(v.scanTaskParams({ "Access-Key": "k" }).ok, false);
});

test("sensitive: deny-list extension hook", () => {
  const v = new SensitiveDataValidator(["mysecretfield"]);
  assert.equal(v.scanTaskParams({ mysecretfield: "v" }).ok, false);
  // chainable addKeyword
  const v2 = new SensitiveDataValidator().addKeyword("custompin");
  assert.equal(v2.scanTaskParams({ custompin: "1234" }).ok, false);
});
