package io.github.pinpols.batch.loadtest.simulations;

import io.github.pinpols.batch.loadtest.GatlingConfig;
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
 * Fixed write pressure plus scheduler/backlog reads.
 *
 * <p>This scenario does not pretend to prove scheduling correctness by itself. Run it together with
 * {@code load-tests/scripts/sample-scheduler-backlog.sh}; Gatling gives endpoint latency/error
 * curves, while the sampler gives WAITING/READY/RUNNING/backlog slope and completion throughput.
 */
public class SchedulingBacklogUnderLoadSimulation extends Simulation {

  private static final String AUTH_TOKEN =
      System.getProperty("console.accessToken") != null
          ? "Bearer " + System.getProperty("console.accessToken")
          : System.getProperty("console.authToken", "Bearer load-test-token");

  private final HttpProtocolBuilder triggerProtocol =
      http
          .baseUrl(GatlingConfig.TRIGGER_BASE_URL)
          .acceptHeader("application/json")
          .contentTypeHeader("application/json")
          .shareConnections();

  private final HttpProtocolBuilder consoleProtocol =
      http
          .baseUrl(GatlingConfig.CONSOLE_BASE_URL)
          .acceptHeader("application/json")
          .header("Authorization", AUTH_TOKEN)
          .shareConnections();

  private final Iterator<Map<String, Object>> launchFeeder =
      Stream.generate(
              () ->
                  Map.<String, Object>of(
                      "idempotencyKey", UUID.randomUUID().toString(),
                      "requestId", UUID.randomUUID().toString(),
                      "traceId",
                      "sbl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)))
          .iterator();

  private final String launchBody =
      """
            {
              "tenantId": "%s",
              "jobCode": "%s",
              "bizDate": "%s",
              "triggerType": "API",
              "params": %s
            }
            """
          .formatted(
              GatlingConfig.TENANT_ID,
              GatlingConfig.JOB_CODE,
              GatlingConfig.BIZ_DATE,
              GatlingConfig.LAUNCH_PARAMS_JSON);

  private final ChainBuilder launch =
      feed(launchFeeder)
          .exec(
              http("POST /api/triggers/launch")
                  .post("/api/triggers/launch")
                  .header("X-Internal-Secret", GatlingConfig.INTERNAL_SECRET)
                  .header("Idempotency-Key", "#{idempotencyKey}")
                  .header("X-Request-Id", "#{requestId}")
                  .header("X-Trace-Id", "#{traceId}")
                  .body(StringBody(launchBody))
                  .check(status().in(200, 201)));

  private final ChainBuilder internalBacklogReads =
      exec(
              http("GET /internal/scheduler/snapshot")
                  .get(GatlingConfig.ORCHESTRATOR_BASE_URL + "/internal/scheduler/snapshot")
                  .header("X-Internal-Secret", GatlingConfig.INTERNAL_SECRET)
                  .queryParam("tenantId", GatlingConfig.TENANT_ID)
                  .check(status().is(200)))
          .exec(
              http("GET /internal/scheduler/snapshot/history")
                  .get(GatlingConfig.ORCHESTRATOR_BASE_URL + "/internal/scheduler/snapshot/history")
                  .header("X-Internal-Secret", GatlingConfig.INTERNAL_SECRET)
                  .queryParam("tenantId", GatlingConfig.TENANT_ID)
                  .queryParam("limit", "20")
                  .check(status().is(200)));

  private final ChainBuilder consoleReads =
      GatlingConfig.SCHEDULING_CONSOLE_READS_ENABLED
          ? exec(
                  http("GET /api/console/scheduler/status")
                      .get("/api/console/scheduler/status")
                      .check(status().is(200)))
              .exec(
                  http("GET /api/console/ops/triggers")
                      .get("/api/console/ops/triggers")
                      .queryParam("tenantId", GatlingConfig.TENANT_ID)
                      .check(status().is(200)))
              .exec(
                  http("GET /api/console/queries/partitions WAITING")
                      .get("/api/console/queries/partitions")
                      .queryParam("tenantId", GatlingConfig.TENANT_ID)
                      .queryParam("partitionStatus", "WAITING")
                      .queryParam("pageNo", "1")
                      .queryParam("pageSize", "50")
                      .check(status().is(200)))
              .exec(
                  http("GET /api/console/queries/partitions READY")
                      .get("/api/console/queries/partitions")
                      .queryParam("tenantId", GatlingConfig.TENANT_ID)
                      .queryParam("partitionStatus", "READY")
                      .queryParam("pageNo", "1")
                      .queryParam("pageSize", "50")
                      .check(status().is(200)))
              .exec(
                  http("GET /api/console/queries/retries WAITING")
                      .get("/api/console/queries/retries")
                      .queryParam("tenantId", GatlingConfig.TENANT_ID)
                      .queryParam("retryStatus", "WAITING")
                      .queryParam("pageNo", "1")
                      .queryParam("pageSize", "50")
                      .check(status().is(200)))
              .exec(
                  http("GET /api/console/queries/catch-up-approvals")
                      .get("/api/console/queries/catch-up-approvals")
                      .queryParam("tenantId", GatlingConfig.TENANT_ID)
                      .queryParam("pageNo", "1")
                      .queryParam("pageSize", "50")
                      .check(status().is(200)))
          : exec(session -> session);

  private final ChainBuilder backlogReads =
      internalBacklogReads
          .exec(
              consoleReads);

  private final ScenarioBuilder launchScenario =
      scenario("Scheduling write pressure").exec(launch);

  private final ScenarioBuilder readScenario =
      scenario("Scheduling backlog reads").exec(backlogReads);

  {
    setUp(
            launchScenario
                .injectOpen(
                    constantUsersPerSec(GatlingConfig.SCHEDULING_LAUNCH_RPS)
                        .during(GatlingConfig.DURATION_SECONDS))
                .protocols(triggerProtocol),
            readScenario
                .injectOpen(
                    constantUsersPerSec(GatlingConfig.SCHEDULING_READ_RPS)
                        .during(GatlingConfig.DURATION_SECONDS))
                .protocols(consoleProtocol))
        .maxDuration(Duration.ofSeconds(GatlingConfig.DURATION_SECONDS + 60L))
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
