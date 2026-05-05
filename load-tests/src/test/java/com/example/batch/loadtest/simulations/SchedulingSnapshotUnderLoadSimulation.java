package com.example.batch.loadtest.simulations;

import com.example.batch.loadtest.GatlingConfig;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Mixed load: sustained job launches (trigger) while polling orchestrator scheduler snapshot
 * ({@code GET /internal/scheduler/snapshot}). Exercises read latency on an internal scheduling probe
 * under write pressure.
 *
 * <p>Run:
 *
 * <pre>
 *   mvn gatling:test -Dsimulation=SchedulingSnapshotUnderLoadSimulation \
 *       -DjobCode=E2E_IMPORT_LOAD -DtenantId=t1 -Dusers.peak=30 -Dduration.seconds=180 \
 *       -Dorchestrator.baseUrl=http://localhost:18082
 * </pre>
 */
public class SchedulingSnapshotUnderLoadSimulation extends Simulation {

  private final HttpProtocolBuilder triggerProtocol =
      http
          .baseUrl(GatlingConfig.TRIGGER_BASE_URL)
          .acceptHeader("application/json")
          .contentTypeHeader("application/json")
          .shareConnections();

  private final HttpProtocolBuilder orchestratorProtocol =
      http
          .baseUrl(GatlingConfig.ORCHESTRATOR_BASE_URL)
          .acceptHeader("application/json")
          .header("X-Internal-Secret", GatlingConfig.INTERNAL_SECRET)
          .shareConnections();

  private final Iterator<Map<String, Object>> launchFeeder =
      Stream.generate(
              () ->
                  Map.<String, Object>of(
                      "idempotencyKey", UUID.randomUUID().toString(),
                      "requestId", UUID.randomUUID().toString()))
          .iterator();

  private final String launchBody =
      """
            {
              "tenantId": "%s",
              "jobCode": "%s",
              "bizDate": "%s",
              "triggerType": "API",
              "params": {}
            }
            """
          .formatted(GatlingConfig.TENANT_ID, GatlingConfig.JOB_CODE, GatlingConfig.BIZ_DATE);

  private final ChainBuilder launch =
      feed(launchFeeder)
          .exec(
              http("POST /api/triggers/launch")
                  .post("/api/triggers/launch")
                  .header("X-Internal-Secret", GatlingConfig.INTERNAL_SECRET)
                  .header("Idempotency-Key", "#{idempotencyKey}")
                  .header("X-Request-Id", "#{requestId}")
                  .body(StringBody(launchBody))
                  .check(status().in(200, 201)))
          .pause(Duration.ofSeconds(1));

  private final ChainBuilder schedulerSnapshot =
      exec(
              http("GET /internal/scheduler/snapshot")
                  .get("/internal/scheduler/snapshot")
                  .queryParam("tenantId", GatlingConfig.TENANT_ID)
                  .check(status().is(200))
                  .check(jsonPath("$.tenantId").is(GatlingConfig.TENANT_ID)))
          .pause(Duration.ofMillis(500));

  private final ScenarioBuilder launchScenario =
      scenario("Write: launch under load").forever().on(launch);

  private final ScenarioBuilder snapshotScenario =
      scenario("Read: scheduler snapshot").forever().on(schedulerSnapshot);

  private static final int STEP_DURATION_SECONDS = 60;

  private static int launchBranchUsers(int stepTotal) {
    return Math.max(1, (int) Math.round(stepTotal * 0.30));
  }

  private static int snapshotBranchUsers(int stepTotal) {
    return stepTotal - launchBranchUsers(stepTotal);
  }

  {
    setUp(
            launchScenario
                .injectOpen(
                    stressPeakUsers(launchBranchUsers(25)).during(STEP_DURATION_SECONDS),
                    stressPeakUsers(launchBranchUsers(50)).during(STEP_DURATION_SECONDS),
                    stressPeakUsers(launchBranchUsers(100)).during(STEP_DURATION_SECONDS),
                    stressPeakUsers(launchBranchUsers(150)).during(STEP_DURATION_SECONDS),
                    stressPeakUsers(launchBranchUsers(200)).during(STEP_DURATION_SECONDS))
                .protocols(triggerProtocol),
            snapshotScenario
                .injectOpen(
                    stressPeakUsers(snapshotBranchUsers(25)).during(STEP_DURATION_SECONDS),
                    stressPeakUsers(snapshotBranchUsers(50)).during(STEP_DURATION_SECONDS),
                    stressPeakUsers(snapshotBranchUsers(100)).during(STEP_DURATION_SECONDS),
                    stressPeakUsers(snapshotBranchUsers(150)).during(STEP_DURATION_SECONDS),
                    stressPeakUsers(snapshotBranchUsers(200)).during(STEP_DURATION_SECONDS))
                .protocols(orchestratorProtocol))
        .maxDuration(Duration.ofSeconds(STEP_DURATION_SECONDS * 5L + 30L))
        .assertions(
            details("POST /api/triggers/launch")
                .responseTime()
                .percentile(95)
                .lt(GatlingConfig.WRITE_P95_MS),
            details("GET /internal/scheduler/snapshot")
                .responseTime()
                .percentile(99)
                .lt(GatlingConfig.READ_P99_MS),
            global().failedRequests().percent().lt(GatlingConfig.MAX_ERROR_RATE_PCT));
  }
}
