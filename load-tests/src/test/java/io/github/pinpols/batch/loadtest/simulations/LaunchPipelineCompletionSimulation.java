package io.github.pinpols.batch.loadtest.simulations;

import io.github.pinpols.batch.loadtest.GatlingConfig;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * End-to-end sampling from trigger launch until the instance reaches a terminal status visible via
 * console batch-status (requires workers to progress CLAIM→report paths).
 *
 * <p>Metrics: Gatling group {@code pipeline_completion} captures wall-clock over that interval per
 * virtual user.
 *
 * <p>Run:
 *
 * <pre>
 *   mvn gatling:test -Dsimulation=LaunchPipelineCompletionSimulation \
 *       -DjobCode=E2E_IMPORT_LOAD -DtenantId=t1 \
 *       -Dpipeline.completion.users=10 -Dramp.seconds=60 \
 *       -Dconsole.authToken=Bearer &lt;token&gt;
 * </pre>
 */
public class LaunchPipelineCompletionSimulation extends Simulation {

  private static final Set<String> TERMINAL_STATUSES =
      Set.of("SUCCESS", "FAILED", "PARTIAL_FAILED", "CANCELLED", "TERMINATED");

  private static final String AUTH_TOKEN =
      System.getProperty("console.accessToken") != null
          ? "Bearer " + System.getProperty("console.accessToken")
          : System.getProperty("console.authToken", "Bearer load-test-token");

  private final HttpProtocolBuilder httpProtocol =
      http
          .acceptHeader("application/json")
          .contentTypeHeader("application/json")
          .shareConnections();

  private final Iterator<Map<String, Object>> feeder =
      Stream.generate(
              () ->
                  Map.<String, Object>of(
                      "idempotencyKey", UUID.randomUUID().toString(),
                      "requestId", UUID.randomUUID().toString(),
                      "traceId",
                      "ltp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)))
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
      feed(feeder)
          .exec(
              http("POST /api/triggers/launch")
                  .post(GatlingConfig.TRIGGER_BASE_URL + "/api/triggers/launch")
                  .header("X-Internal-Secret", GatlingConfig.INTERNAL_SECRET)
                  .header("Idempotency-Key", "#{idempotencyKey}")
                  .header("X-Request-Id", "#{requestId}")
                  .header("X-Trace-Id", "#{traceId}")
                  .body(StringBody(launchBody))
                  .check(status().in(200, 201))
                  .check(jsonPath("$.data.instanceNo").exists())
                  .check(jsonPath("$.data.instanceNo").saveAs("instanceNo")));

  private final ChainBuilder pollUntilTerminal =
      repeat(GatlingConfig.PIPELINE_MAX_POLLS)
          .on(
              pause(Duration.ofSeconds(GatlingConfig.PIPELINE_POLL_INTERVAL_SEC)),
              exec(
                      http("GET /api/console/queries/instances/batch-status")
                          .get(
                              GatlingConfig.CONSOLE_BASE_URL
                                  + "/api/console/queries/instances/batch-status")
                          .header("Authorization", AUTH_TOKEN)
                          .queryParam("tenantId", GatlingConfig.TENANT_ID)
                          .queryParam("instanceNos", "#{instanceNo}")
                          .check(status().is(200))
                          .check(
                              jsonPath("$.data[0].instanceStatus")
                                  .optional()
                                  .saveAs("jobStatus")))
                  .exitHereIf(
                      session -> {
                        String st = session.getString("jobStatus");
                        return st != null && TERMINAL_STATUSES.contains(st);
                      }));

  private final ScenarioBuilder scenario =
      scenario("Launch pipeline to terminal (poll)")
          .exec(group("pipeline_completion").on(exec(launch), exec(pollUntilTerminal)));

  {
    setUp(
            scenario.injectOpen(
                rampUsers(GatlingConfig.PIPELINE_COMPLETION_USERS)
                    .during(GatlingConfig.RAMP_SECONDS)))
        .protocols(httpProtocol)
        .maxDuration(
            Duration.ofSeconds(
                GatlingConfig.RAMP_SECONDS
                    + GatlingConfig.PIPELINE_MAX_POLLS
                        * (long) GatlingConfig.PIPELINE_POLL_INTERVAL_SEC
                    + 120L))
        .assertions(global().failedRequests().percent().lt(GatlingConfig.MAX_ERROR_RATE_PCT));
  }
}
