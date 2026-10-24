package io.github.pinpols.batch.loadtest.simulations;

import io.github.pinpols.batch.loadtest.GatlingConfig;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * 从 Trigger 准入开始采样，直到根 WorkflowRun 进入终态。
 * 需要 Worker 推进 CLAIM→report 流程。
 *
 * <p>Metrics: Gatling group {@code pipeline_completion} captures wall-clock over that interval per
 * virtual user.
 *
 * <p>Run:
 *
 * <pre>
 *   mvn gatling:test -Dsimulation=LaunchPipelineCompletionSimulation \
 *       -DworkflowDefinitionId=123 -DtenantId=t1 \
 *       -Dpipeline.completion.users=10 -Dramp.seconds=60 \
 *       -Dconsole.authToken=Bearer &lt;token&gt;
 * </pre>
 */
public class LaunchPipelineCompletionSimulation extends Simulation {

  private static final Set<String> TERMINAL_STATUSES =
      Set.of("SUCCESS", "FAILED", "TERMINATED");

  private static final String AUTH_TOKEN =
      System.getProperty("console.accessToken") != null
          ? "Bearer " + System.getProperty("console.accessToken")
          : System.getProperty("console.authToken", "Bearer load-test-token");

  private static final String[] FILE_IDS =
      Arrays.stream(System.getProperty("launch.fileIdsCsv", "").split(","))
          .map(String::trim)
          .filter(value -> !value.isEmpty())
          .toArray(String[]::new);

  private final HttpProtocolBuilder httpProtocol =
      http
          .acceptHeader("application/json")
          .contentTypeHeader("application/json")
          .shareConnections();

  private final AtomicInteger feederIndex = new AtomicInteger();

  private final Iterator<Map<String, Object>> feeder =
      Stream.generate(this::nextFeederRecord).iterator();

  private final String launchBody =
      """
            {
              "tenantId": "%s",
              "workflowDefinitionId": %s,
              "bizDate": "%s",
              "triggerType": "API",
              "params": %s
            }
            """
          .formatted(
              GatlingConfig.TENANT_ID,
              GatlingConfig.requiredWorkflowDefinitionId(),
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
                      http("GET /api/console/queries/workflow-runs/{id}")
                          .get(
                              GatlingConfig.CONSOLE_BASE_URL
                                  + "/api/console/queries/workflow-runs/#{instanceNo}")
                          .header("Authorization", AUTH_TOKEN)
                          .queryParam("tenantId", GatlingConfig.TENANT_ID)
                          .check(status().is(200))
                          .check(
                              jsonPath("$.data.runStatus")
                                  .optional()
                                  .saveAs("workflowStatus")))
                  .exitHereIf(
                      session -> {
                        String st = session.getString("workflowStatus");
                        return st != null && TERMINAL_STATUSES.contains(st);
                      }))
          .exec(
              session -> {
                String status = session.getString("workflowStatus");
                return status != null && TERMINAL_STATUSES.contains(status)
                    ? session
                    : session.markAsFailed();
              });

  private final ScenarioBuilder scenario =
      scenario("Launch pipeline to terminal (poll)")
          .exec(
              group("pipeline_completion")
                  .on(
                      exec(launch),
                      // launch 失败时保留 POST 失败指标，但不能继续用缺失的 instanceNo 轮询。
                      exitHereIf(session -> !session.contains("instanceNo")),
                      exec(pollUntilTerminal)));

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
        .assertions(GatlingConfig.maxErrorRateAssertion());
  }

  private Map<String, Object> nextFeederRecord() {
    Map<String, Object> values = new HashMap<>();
    values.put("idempotencyKey", UUID.randomUUID().toString());
    values.put("requestId", UUID.randomUUID().toString());
    values.put(
        "traceId", "ltp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    if (FILE_IDS.length > 0) {
      values.put("fileId", FILE_IDS[Math.floorMod(feederIndex.getAndIncrement(), FILE_IDS.length)]);
    }
    return values;
  }
}
