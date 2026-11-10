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
 * 从 Trigger 准入开始采样；启用轮询时等待对应根 WorkflowRun 进入终态。
 * 轮询模式需要 Worker 推进 CLAIM→report 流程。
 *
 * <p>{@code pipeline_completion} 记录每个请求到终态或等待结束的耗时，内部结果组区分成功、失败、
 * 终止、dry-run 和超时。场景仅在所有请求到达业务成功终态时通过；实际数据副作用仍需单独对账。
 * 零轮询模式只验证触发受理，业务终态与副作用须由外部脚本对账。
 *
 * <p>Run:
 *
 * <pre>
 *   mvn gatling:test -Dsimulation=LaunchPipelineCompletionSimulation \
 *       -DworkflowDefinitionId=123 -DtenantId=t1 \
 *       -Dpipeline.completion.users=10 -Dramp.seconds=60 \
 *       -Dconsole.accessToken=&lt;token&gt;
 * </pre>
 */
public class LaunchPipelineCompletionSimulation extends Simulation {

  private static final Set<String> TERMINAL_STATUSES =
      Set.of("SUCCESS", "FAILED", "TERMINATED", "SUCCESS_DRY_RUN", "FAILED_DRY_RUN");

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
      exec(session -> session.set("pipelinePollCount", 0))
          .asLongAs(
              session -> {
                String status = session.getString("workflowStatus");
                return session.getInt("pipelinePollCount") < GatlingConfig.PIPELINE_MAX_POLLS
                    && (status == null || !TERMINAL_STATUSES.contains(status));
              })
          .on(
              pause(Duration.ofSeconds(GatlingConfig.PIPELINE_POLL_INTERVAL_SEC)),
              exec(session -> session.set("pipelinePollCount", session.getInt("pipelinePollCount") + 1)),
              exec(
                      http("GET /api/console/queries/workflow-runs?requestId")
                          .get(GatlingConfig.CONSOLE_BASE_URL + "/api/console/queries/workflow-runs")
                          .queryParam("tenantId", GatlingConfig.TENANT_ID)
                          .queryParam("requestId", "#{instanceNo}")
                          .check(status().is(200))
                          .check(
                              jsonPath("$.data.items[0].runStatus")
                                  .optional()
                                  .saveAs("workflowStatus"))))
          .group(
              session -> {
                String status = session.getString("workflowStatus");
                String result = status != null && TERMINAL_STATUSES.contains(status)
                    ? status : "TIMEOUT";
                return "pipeline_result_" + result;
              })
          .on(exec(session -> "SUCCESS".equals(session.getString("workflowStatus"))
              ? session : session.markAsFailed()));

  private final ScenarioBuilder scenario =
      GatlingConfig.PIPELINE_MAX_POLLS == 0
          ? scenario("Launch acceptance only")
              .exec(group("launch_acceptance").on(exec(launch)))
          : scenario("Launch pipeline to terminal (poll)")
              .exec(
                  group("pipeline_completion")
                      .on(
                          exec(launch),
                          // launch 失败时保留 POST 失败指标，但不能继续用缺失的请求标识轮询。
                          exitHereIf(session -> !session.contains("instanceNo")),
                          exec(addCookie(GatlingConfig.consoleAuthCookie())),
                          exec(pollUntilTerminal)));

  {
    var setup = setUp(
            scenario.injectOpen(
                rampUsers(GatlingConfig.PIPELINE_COMPLETION_USERS)
                    .during(GatlingConfig.RAMP_SECONDS)))
        .protocols(httpProtocol)
        .maxDuration(
            Duration.ofSeconds(
                GatlingConfig.RAMP_SECONDS
                    + GatlingConfig.PIPELINE_MAX_POLLS
                        * (long) GatlingConfig.PIPELINE_POLL_INTERVAL_SEC
                    + 120L));
    if (GatlingConfig.PIPELINE_MAX_POLLS == 0) {
      setup.assertions(
          GatlingConfig.maxErrorRateAssertion(),
          details("launch_acceptance").failedRequests().count().is(0L),
          details("launch_acceptance").successfulRequests().count()
              .is((long) GatlingConfig.PIPELINE_COMPLETION_USERS));
    } else {
      setup.assertions(
          GatlingConfig.maxErrorRateAssertion(),
          details("pipeline_completion").failedRequests().count().is(0L),
          details("pipeline_completion", "pipeline_result_SUCCESS")
              .successfulRequests().count().is((long) GatlingConfig.PIPELINE_COMPLETION_USERS));
    }
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
