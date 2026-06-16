/**
 * §1.2 conformance runner: load all 12 contract fixtures, drive the decision
 * core from each fixture's `given`/`when`, and assert that EVERY field present
 * in `then.expect` deep-equals the computed result's same field.
 *
 * The dispatch picks a decision function from the protocol shape of `when`
 * (channel / path / status / response body) — NOT from `then.expect`. The
 * decision functions contain the real logic; this runner only routes inputs and
 * flattens outputs into the closed `then.expect` vocabulary.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";

import {
  classifyHttp,
  applyHeartbeatDirective,
  applyRenew,
  decideBackpressure,
  planStop,
  decideRegister,
} from "../src/decide.ts";

const FIXTURES_DIR = new URL(
  "../../../docs/api/sdk-contract-fixtures/",
  import.meta.url,
);

interface Fixture {
  scenario: string;
  given: { config: Record<string, any>; state?: Record<string, any> };
  when: {
    channel: "http" | "kafka";
    method: string;
    path?: string;
    body?: any;
    responseStatus?: number | null;
    responseBody?: any;
  };
  then: { expect: Record<string, any> };
}

/** All the field names the runner can emit, matching the closed expect vocab. */
type ComputedResult = Record<string, unknown>;

/**
 * Route a fixture to the appropriate decision function and flatten its result
 * into the `then.expect` field vocabulary.
 */
function compute(fx: Fixture): ComputedResult {
  const { when, given } = fx;

  // ----- Kafka receive → capacity backpressure -----
  if (when.channel === "kafka") {
    const inFlight = Number(given.state?.inFlight ?? 0);
    const maxConcurrent = Number(given.config?.maxConcurrentTasks ?? Infinity);
    return decideBackpressure(inFlight, maxConcurrent);
  }

  // ----- HTTP -----
  const path = when.path ?? "";
  const status = when.responseStatus ?? 0;

  // register
  if (path.endsWith("/register")) {
    // idempotent reuse signal: platform already had a (tenant, workerCode)
    // record — fixtures encode this via given.state describing prior existence.
    const idempotent = given.state != null;
    return decideRegister(idempotent);
  }

  // heartbeat
  if (path.includes("/heartbeat")) {
    return applyHeartbeatDirective(when.responseBody ?? {});
  }

  // deactivate → graceful stop. stop timeout: prefer config, else fixture withinMs
  // is the contractual bound; default 30s SIGTERM grace.
  if (path.includes("/deactivate")) {
    const timeoutMs =
      Number(given.config?.stopTimeoutMs) ||
      30000;
    return planStop(timeoutMs);
  }

  // renew
  if (path.includes("/renew")) {
    return applyRenew(when.responseBody ?? {});
  }

  // claim / report and any other /internal/* call → status classification
  if (path.includes("/claim") || path.includes("/report")) {
    const baseMs = Number(given.config?.retryBaseDelayMs) || undefined;
    const maxAttempts = Number(given.config?.retryMaxAttempts) || undefined;
    return classifyHttp(status, 0, baseMs as number, maxAttempts as number);
  }

  throw new Error(`no decision route for fixture ${fx.scenario} (path=${path})`);
}

const EXPECTED_FIXTURE_COUNT = 12;

const fixtureFiles = readdirSync(FIXTURES_DIR)
  .filter((f) => /^\d.*\.json$/.test(f))
  .sort();

test("all 12 contract fixtures are present", () => {
  assert.equal(
    fixtureFiles.length,
    EXPECTED_FIXTURE_COUNT,
    `expected ${EXPECTED_FIXTURE_COUNT} fixtures, found ${fixtureFiles.length}: ${fixtureFiles.join(", ")}`,
  );
});

for (const file of fixtureFiles) {
  const fx: Fixture = JSON.parse(
    readFileSync(new URL(file, FIXTURES_DIR), "utf8"),
  );

  test(`conformance: ${file} (${fx.scenario})`, () => {
    const computed = compute(fx);
    const expect = fx.then.expect;

    assert.ok(
      Object.keys(expect).length > 0,
      `${file}: then.expect is empty`,
    );

    // assert every field PRESENT in then.expect; absent fields are unconstrained
    for (const [field, expectedValue] of Object.entries(expect)) {
      assert.ok(
        field in computed,
        `${file} [${fx.scenario}]: decision core did not produce field '${field}' (computed=${JSON.stringify(computed)})`,
      );
      assert.deepEqual(
        computed[field],
        expectedValue,
        `${file} [${fx.scenario}]: field '${field}' mismatch — computed ${JSON.stringify(computed[field])}, expected ${JSON.stringify(expectedValue)}`,
      );
    }
  });
}
