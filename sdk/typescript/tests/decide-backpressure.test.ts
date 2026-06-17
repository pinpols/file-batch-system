/**
 * decideBackpressure max/2 hysteresis (#8), aligned with Java
 * KafkaTaskConsumer.applyBackpressure: pause >= max; resume only when in-flight
 * drops below max/2; stay paused in the [max/2, max) band so the max-1 / max
 * boundary does not thrash pause/resume.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { decideBackpressure } from "../src/decide.ts";

const cases: Array<{
  name: string;
  inFlight: number;
  max: number;
  paused: boolean;
  action: "backpressure" | "none";
  kafka?: string;
}> = [
  // pause edge: at or over capacity always pauses (fixture 11 contract).
  { name: "at-capacity pauses", inFlight: 4, max: 4, paused: false, action: "backpressure", kafka: "pause" },
  { name: "over-capacity pauses", inFlight: 5, max: 4, paused: false, action: "backpressure", kafka: "pause" },
  // already paused, at capacity -> still pause (idempotent), never resume.
  { name: "paused at capacity stays pause", inFlight: 4, max: 4, paused: true, action: "backpressure", kafka: "pause" },
  // max-1 just below cap while paused: must NOT resume (in hysteresis band).
  { name: "paused max-1 no resume", inFlight: 3, max: 4, paused: true, action: "none" },
  // in the [max/2, max) band while paused: hold paused, no flapping.
  { name: "paused at max/2 holds (=2, not < 2)", inFlight: 2, max: 4, paused: true, action: "none" },
  // drop below max/2 while paused -> resume.
  { name: "paused below max/2 resumes", inFlight: 1, max: 4, paused: true, action: "backpressure", kafka: "resume" },
  // not paused and below cap -> nothing to do.
  { name: "not paused below cap none", inFlight: 1, max: 4, paused: false, action: "none" },
  // max=10 ladder mirrors the Java hysteresis test: 6 holds, 5 holds, 4 resumes.
  { name: "max10 inflight6 holds", inFlight: 6, max: 10, paused: true, action: "none" },
  { name: "max10 inflight5 holds (=5 not < 5)", inFlight: 5, max: 10, paused: true, action: "none" },
  { name: "max10 inflight4 resumes", inFlight: 4, max: 10, paused: true, action: "backpressure", kafka: "resume" },
  // max=1 floors resume threshold at 1: inflight 0 resumes.
  { name: "max1 inflight0 resumes", inFlight: 0, max: 1, paused: true, action: "backpressure", kafka: "resume" },
];

for (const c of cases) {
  test(`decideBackpressure hysteresis: ${c.name}`, () => {
    const d = decideBackpressure(c.inFlight, c.max, c.paused);
    assert.equal(d.action, c.action);
    assert.equal(d.kafka, c.kafka);
  });
}

// default currentlyPaused = false keeps the 2-arg call site backward compatible:
// a not-yet-paused worker below cap does nothing even sitting in the band.
test("decideBackpressure default paused=false: no resume in band", () => {
  const d = decideBackpressure(1, 4);
  assert.equal(d.action, "none");
});
