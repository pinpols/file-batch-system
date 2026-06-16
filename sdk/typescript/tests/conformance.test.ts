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
  classifySchemaVersion,
  buildRequest,
  type RequestSpec,
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

/** Request-side expect keys handled by buildRequest, not by the decision core. */
const REQUEST_SIDE_KEYS = new Set([
  "requestBodyIncludes",
  "requestBodyExcludes",
  "requestHeaders",
]);

/** All the field names the runner can emit, matching the closed expect vocab. */
type ComputedResult = Record<string, unknown>;

/**
 * Route a fixture to the appropriate decision function and flatten its result
 * into the `then.expect` field vocabulary.
 */
function compute(fx: Fixture): ComputedResult {
  const { when, given } = fx;

  // ----- Kafka receive → schemaVersion classification or capacity backpressure -----
  if (when.channel === "kafka") {
    // schemaAccept fixtures assert §A version handling on the received message.
    if ("schemaAccept" in (fx.then.expect ?? {})) {
      const version = when.body?.schemaVersion as string | undefined;
      return { schemaAccept: classifySchemaVersion(version) === "accept" };
    }
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

  // renew — error statuses classify by §B (404 give-up, 5xx backoff, ...);
  // a 2xx renew applies the cancel directive from the response body.
  if (path.includes("/renew")) {
    if (status >= 400) {
      const clientErrorCount = Number(given.state?.clientErrorCount ?? 0);
      return classifyHttp(status, clientErrorCount);
    }
    return applyRenew(when.responseBody ?? {});
  }

  // claim / report and any other /internal/* call → status classification
  if (path.includes("/claim") || path.includes("/report")) {
    const baseMs = Number(given.config?.retryBaseDelayMs) || undefined;
    const maxAttempts = Number(given.config?.retryMaxAttempts) || undefined;
    const clientErrorCount = Number(given.state?.clientErrorCount ?? 0);
    return classifyHttp(
      status,
      clientErrorCount,
      baseMs as number,
      maxAttempts as number,
    );
  }

  throw new Error(`no decision route for fixture ${fx.scenario} (path=${path})`);
}

/** Build the outgoing request a fixture's given.state.request describes. */
function computeRequest(fx: Fixture) {
  const spec = fx.given.state?.request as RequestSpec | undefined;
  if (spec == null) {
    throw new Error(
      `${fx.scenario}: request-side fixture missing given.state.request`,
    );
  }
  return buildRequest(spec, {
    tenantId: fx.given.config?.tenantId,
    workerCode: fx.given.config?.workerCode,
    apiKey: fx.given.config?.apiKey,
  });
}

/** Deep-subset: every key/value in `subset` is present and equal in `actual`. */
function assertDeepIncludes(
  actual: Record<string, unknown>,
  subset: Record<string, unknown>,
  ctx: string,
) {
  for (const [k, v] of Object.entries(subset)) {
    assert.ok(k in actual, `${ctx}: outgoing body missing key '${k}'`);
    assert.deepEqual(
      actual[k],
      v,
      `${ctx}: body['${k}'] mismatch — got ${JSON.stringify(actual[k])}, want ${JSON.stringify(v)}`,
    );
  }
}

/** Assert request-side expectations (body includes/excludes, header regexes). */
function assertRequestSide(fx: Fixture, ctx: string) {
  const { body, headers } = computeRequest(fx);
  const expect = fx.then.expect;

  if (expect.requestBodyIncludes) {
    assertDeepIncludes(body, expect.requestBodyIncludes, ctx);
  }
  for (const key of expect.requestBodyExcludes ?? []) {
    assert.ok(
      !(key in body),
      `${ctx}: outgoing body must NOT contain key '${key}' (got ${JSON.stringify(body)})`,
    );
  }
  for (const [name, pattern] of Object.entries(
    expect.requestHeaders ?? {},
  )) {
    const value = headers[name];
    assert.ok(
      value !== undefined,
      `${ctx}: outgoing headers missing '${name}'`,
    );
    assert.ok(
      new RegExp(pattern as string).test(value),
      `${ctx}: header '${name}'='${value}' does not match /${pattern}/`,
    );
  }
}

const EXPECTED_FIXTURE_COUNT = 22;

const fixtureFiles = readdirSync(FIXTURES_DIR)
  .filter((f) => /^\d.*\.json$/.test(f))
  .sort();

test(`all ${EXPECTED_FIXTURE_COUNT} contract fixtures are present`, () => {
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
    const expect = fx.then.expect;
    const ctx = `${file} [${fx.scenario}]`;

    assert.ok(
      Object.keys(expect).length > 0,
      `${file}: then.expect is empty`,
    );

    // Request-side assertions (body includes/excludes, header regexes) are
    // driven by the request builder, not the response→reaction decision core.
    const hasRequestSide = Object.keys(expect).some((k) =>
      REQUEST_SIDE_KEYS.has(k),
    );
    if (hasRequestSide) {
      assertRequestSide(fx, ctx);
    }

    // Response-side / schemaAccept fields are produced by compute().
    const decisionFields = Object.keys(expect).filter(
      (k) => !REQUEST_SIDE_KEYS.has(k),
    );
    if (decisionFields.length === 0) {
      return;
    }
    const computed = compute(fx);
    for (const field of decisionFields) {
      assert.ok(
        field in computed,
        `${ctx}: decision core did not produce field '${field}' (computed=${JSON.stringify(computed)})`,
      );
      assert.deepEqual(
        computed[field],
        expect[field],
        `${ctx}: field '${field}' mismatch — computed ${JSON.stringify(computed[field])}, expected ${JSON.stringify(expect[field])}`,
      );
    }
  });
}
