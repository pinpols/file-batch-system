package io.github.pinpols.batch.loadtest.simulations;

import io.github.pinpols.batch.loadtest.GatlingConfig;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * 混合控制面压力场景，覆盖 process、dispatch、atomic 和 trigger。
 *
 * <p>This scenario intentionally launches all selected modules inside one Gatling run so the target
 * 系统承受真实的跨模块压力。完成情况由包装脚本通过数据库
 * 轮询终态来衡量；Gatling 负责统计发起和读取的延迟及错误曲线。
 */
public class ControlPlaneMixedPressureSimulation extends Simulation {

  private static final String MODULES =
      System.getProperty("control.modules", "process,dispatch,atomic,trigger");

  /**
   * 业务日基数。默认 1 检查同 Workflow/date 去重；大于 1 时跨模块按全局请求序号轮换日期，
   * 避免 Atomic 与 Trigger 引用同一 Workflow 时复用根运行。独立执行容量验收须配置足够基数。
   */
  private static final int BIZ_DATE_CARDINALITY =
      positiveIntProperty("control.bizDate.cardinality", 1);

  private static final AtomicLong BUSINESS_DATE_SEQUENCE = new AtomicLong();

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

  private static final List<String> DISPATCH_FILE_IDS =
      csvProperty("control.dispatch.fileIdsCsv");

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
        populations, "process", "control.process.workflowDefinitionId", PROCESS_PARAMS, PROCESS_RPS);
    addLaunchPopulation(
        populations, "dispatch", "control.dispatch.workflowDefinitionId", DISPATCH_PARAMS, DISPATCH_RPS);
    addLaunchPopulation(
        populations, "atomic", "control.atomic.workflowDefinitionId", ATOMIC_PARAMS, ATOMIC_RPS);
    addLaunchPopulation(
        populations,
        "trigger",
        "control.trigger.workflowDefinitionId",
        TRIGGER_PARAMS,
        GatlingConfig.SCHEDULING_LAUNCH_RPS);

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
        .assertions(
            // 控制面混压下,launch/claim/report 写延迟会先于错误率劣化逼近瓶颈
            // (单机~20 jobs/s 上限);仅卡错误率会"绿着已逼近瓶颈"。补全局 p95
            // 写延迟门,使容量验收在延迟侧也有硬 SLO(slo.write.p95ms,默认 500ms)。
            global().responseTime().percentile(95).lt(GatlingConfig.WRITE_P95_MS),
            GatlingConfig.maxErrorRateAssertion());
  }

  private static void addLaunchPopulation(
      List<PopulationBuilder> populations,
      String module,
      String workflowDefinitionIdProperty,
      String paramsJson,
      double requestsPerSecond) {
    if (!enabled(module) || requestsPerSecond <= 0) {
      return;
    }
    String workflowDefinitionId =
        GatlingConfig.requiredWorkflowDefinitionId(workflowDefinitionIdProperty);
    ScenarioBuilder scenario =
        scenario("mixed " + module + " launch").exec(launch(module, workflowDefinitionId, paramsJson));
    populations.add(
        scenario.injectOpen(
            constantUsersPerSec(requestsPerSecond).during(GatlingConfig.DURATION_SECONDS)));
  }

  private static ChainBuilder launch(String module, String workflowDefinitionId, String paramsJson) {
    String launchBody =
        """
            {
              "tenantId": "%s",
              "workflowDefinitionId": %s,
              "bizDate": "#{bizDate}",
              "triggerType": "API",
              "params": %s
            }
            """
            .formatted(GatlingConfig.TENANT_ID, workflowDefinitionId, paramsJson);

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
    LocalDate firstBizDate = LocalDate.parse(GatlingConfig.BIZ_DATE);
    AtomicLong sequence = new AtomicLong();
    return Stream.generate(
            () -> {
              long requestIndex = sequence.getAndIncrement();
              LocalDate bizDate =
                  firstBizDate.plusDays(
                      Math.floorMod(BUSINESS_DATE_SEQUENCE.getAndIncrement(), BIZ_DATE_CARDINALITY));
              Map<String, Object> values = new HashMap<>();
              values.put("idempotencyKey", UUID.randomUUID().toString());
              values.put(
                  "requestId", GatlingConfig.RUN_ID + "-" + module + "-" + UUID.randomUUID());
              values.put(
                  "traceId",
                  GatlingConfig.RUN_ID
                      + "-mix-"
                      + module
                      + "-"
                      + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
              values.put("bizDate", bizDate.toString());
              if ("dispatch".equals(module)) {
                if (requestIndex >= DISPATCH_FILE_IDS.size()) {
                  throw new IllegalStateException(
                      "Dispatch file ID fixture exhausted at request " + requestIndex);
                }
                values.put("fileId", DISPATCH_FILE_IDS.get(Math.toIntExact(requestIndex)));
              }
              return values;
            })
        .iterator();
  }

  private static List<String> csvProperty(String name) {
    return Stream.of(System.getProperty(name, "").split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .toList();
  }

  private static int positiveIntProperty(String name, int defaultValue) {
    int value = Integer.parseInt(System.getProperty(name, String.valueOf(defaultValue)));
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be a positive integer");
    }
    return value;
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
