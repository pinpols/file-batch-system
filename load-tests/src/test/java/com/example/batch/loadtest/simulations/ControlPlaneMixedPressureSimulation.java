package com.example.batch.loadtest.simulations;

import com.example.batch.loadtest.GatlingConfig;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Mixed control-plane pressure for process / dispatch / atomic / trigger.
 *
 * <p>This scenario intentionally launches all selected modules inside one Gatling run so the target
 * system sees real cross-module pressure. Completion is measured by the wrapper script through DB
 * terminal-state polling; Gatling owns launch/read latency and error curves.
 */
public class ControlPlaneMixedPressureSimulation extends Simulation {

  private static final String MODULES =
      System.getProperty("control.modules", "process,dispatch,atomic,trigger");

  private static final String PROCESS_JOB_CODE =
      System.getProperty("control.process.jobCode", "lt_process_sql_job");

  private static final String DISPATCH_JOB_CODE =
      System.getProperty("control.dispatch.jobCode", "lt_dispatch_local_job");

  private static final String ATOMIC_JOB_CODE =
      System.getProperty("control.atomic.jobCode", "atomic_sql_demo");

  private static final String TRIGGER_JOB_CODE =
      System.getProperty("control.trigger.jobCode", "atomic_sql_demo");

  private static final double PROCESS_RPS =
      Double.parseDouble(System.getProperty("control.process.rps", "1.0"));

  private static final double DISPATCH_RPS =
      Double.parseDouble(System.getProperty("control.dispatch.rps", "1.0"));

  private static final double ATOMIC_RPS =
      Double.parseDouble(System.getProperty("control.atomic.rps", "1.0"));

  private static final String PROCESS_PARAMS =
      paramsJson("control.process.paramsJsonFile", "control.process.paramsJson");

  private static final String DISPATCH_PARAMS =
      paramsJson("control.dispatch.paramsJsonFile", "control.dispatch.paramsJson");

  private static final String ATOMIC_PARAMS =
      paramsJson("control.atomic.paramsJsonFile", "control.atomic.paramsJson");

  private static final String TRIGGER_PARAMS =
      paramsJson("control.trigger.paramsJsonFile", "control.trigger.paramsJson");

  private final HttpProtocolBuilder httpProtocol =
      http.acceptHeader("application/json").contentTypeHeader("application/json").shareConnections();

  private final ChainBuilder schedulerReads =
      exec(
              http("mixed / GET /internal/scheduler/snapshot")
                  .get(GatlingConfig.ORCHESTRATOR_BASE_URL + "/internal/scheduler/snapshot")
                  .header("X-Internal-Secret", GatlingConfig.INTERNAL_SECRET)
                  .queryParam("tenantId", GatlingConfig.TENANT_ID)
                  .check(status().is(200)))
          .exec(
              http("mixed / GET /internal/scheduler/snapshot/history")
                  .get(GatlingConfig.ORCHESTRATOR_BASE_URL + "/internal/scheduler/snapshot/history")
                  .header("X-Internal-Secret", GatlingConfig.INTERNAL_SECRET)
                  .queryParam("tenantId", GatlingConfig.TENANT_ID)
                  .queryParam("limit", "20")
                  .check(status().is(200)));

  {
    List<PopulationBuilder> populations = new ArrayList<>();
    addLaunchPopulation(
        populations, "process", PROCESS_JOB_CODE, PROCESS_PARAMS, PROCESS_RPS);
    addLaunchPopulation(
        populations, "dispatch", DISPATCH_JOB_CODE, DISPATCH_PARAMS, DISPATCH_RPS);
    addLaunchPopulation(populations, "atomic", ATOMIC_JOB_CODE, ATOMIC_PARAMS, ATOMIC_RPS);
    addLaunchPopulation(
        populations, "trigger", TRIGGER_JOB_CODE, TRIGGER_PARAMS, GatlingConfig.SCHEDULING_LAUNCH_RPS);

    if (enabled("trigger") && GatlingConfig.SCHEDULING_READ_RPS > 0) {
      ScenarioBuilder reads = scenario("mixed trigger scheduler reads").exec(schedulerReads);
      populations.add(
          reads.injectOpen(
              constantUsersPerSec(GatlingConfig.SCHEDULING_READ_RPS)
                  .during(GatlingConfig.DURATION_SECONDS)));
    }

    setUp(populations.toArray(PopulationBuilder[]::new))
        .protocols(httpProtocol)
        .maxDuration(Duration.ofSeconds(GatlingConfig.DURATION_SECONDS + 60L))
        .assertions(global().failedRequests().percent().lt(GatlingConfig.MAX_ERROR_RATE_PCT));
  }

  private static void addLaunchPopulation(
      List<PopulationBuilder> populations,
      String module,
      String jobCode,
      String paramsJson,
      double requestsPerSecond) {
    if (!enabled(module) || requestsPerSecond <= 0) {
      return;
    }
    ScenarioBuilder scenario =
        scenario("mixed " + module + " launch").exec(launch(module, jobCode, paramsJson));
    populations.add(
        scenario.injectOpen(
            constantUsersPerSec(requestsPerSecond).during(GatlingConfig.DURATION_SECONDS)));
  }

  private static ChainBuilder launch(String module, String jobCode, String paramsJson) {
    String launchBody =
        """
            {
              "tenantId": "%s",
              "jobCode": "%s",
              "bizDate": "%s",
              "triggerType": "API",
              "params": %s
            }
            """
            .formatted(GatlingConfig.TENANT_ID, jobCode, GatlingConfig.BIZ_DATE, paramsJson);

    return feed(feeder(module))
        .exec(
            http("mixed " + module + " / POST /api/triggers/launch")
                .post(GatlingConfig.TRIGGER_BASE_URL + "/api/triggers/launch")
                .header("X-Internal-Secret", GatlingConfig.INTERNAL_SECRET)
                .header("Idempotency-Key", "#{idempotencyKey}")
                .header("X-Request-Id", "#{requestId}")
                .header("X-Trace-Id", "#{traceId}")
                .body(StringBody(launchBody))
                .check(status().in(200, 201)));
  }

  private static Iterator<Map<String, Object>> feeder(String module) {
    return Stream.generate(
            () ->
                Map.<String, Object>of(
                    "idempotencyKey", UUID.randomUUID().toString(),
                    "requestId", module + "-" + UUID.randomUUID(),
                    "traceId",
                    "mix-"
                        + module
                        + "-"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 16)))
        .iterator();
  }

  private static boolean enabled(String module) {
    return ("," + MODULES + ",").contains("," + module + ",");
  }

  private static String paramsJson(String fileProperty, String jsonProperty) {
    String file = System.getProperty(fileProperty);
    if (file == null || file.isBlank()) {
      return System.getProperty(jsonProperty, "{}");
    }
    try {
      return Files.readString(Path.of(file));
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read " + fileProperty + "=" + file, e);
    }
  }
}
