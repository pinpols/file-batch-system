package io.github.pinpols.batch.loadtest.simulations;

import io.github.pinpols.batch.loadtest.GatlingConfig;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * 负载测试:ADR-029 专用 SPI worker 原子任务派发吞吐/时延。
 *   POST /api/triggers/launch  (batch-trigger)  → job_type=ATOMIC → batch.task.dispatch.atomic
 *
 * <p>前置:目标库已 seed 原子任务 job(scripts/db/test-seed/platform_seed.sql 的 atomic_sql_demo,
 * default_params 自带 {taskType:sql, sql:'SELECT 1'},故 launch params 留空即可),且 batch-worker-atomic
 * 进程在跑(start-all.sh 已含 worker-atomic)。执行器需在该 worker 上 enable(默认全关)。
 *
 * <p>本 sim 压的是 launch 派发路径的 SLO(写 p95 + 错误率);执行完成的端到端时延用
 * {@code LaunchPipelineCompletionSimulation} 同款 poll 模式另测。
 *
 * <p>Run:
 * <pre>
 *   mvn gatling:test -Dsimulation=AtomicTaskDispatchSimulation \
 *       -DjobCode=atomic_sql_demo -DtenantId=default-tenant \
 *       -Dusers.peak=50 -Dduration.seconds=180
 * </pre>
 */
public class AtomicTaskDispatchSimulation extends Simulation {

    private static final String SPI_JOB_CODE =
            System.getProperty("jobCode", "atomic_sql_demo");

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(GatlingConfig.TRIGGER_BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .shareConnections();

    private final Iterator<Map<String, Object>> feeder = Stream.generate(() ->
            Map.<String, Object>of(
                    "idempotencyKey", UUID.randomUUID().toString(),
                    "requestId", UUID.randomUUID().toString(),
                    "traceId", "lt-spi-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
            )
    ).iterator();

    private final String requestBody = """
            {
              "tenantId": "%s",
              "jobCode": "%s",
              "bizDate": "%s",
              "triggerType": "API",
              "params": %s
            }
            """.formatted(
                    GatlingConfig.TENANT_ID,
                    SPI_JOB_CODE,
                    GatlingConfig.BIZ_DATE,
                    GatlingConfig.LAUNCH_PARAMS_JSON);

    private final ChainBuilder launchSpiTask = feed(feeder)
            .exec(
                    http("POST /api/triggers/launch (SPI)")
                            .post("/api/triggers/launch")
                            .header("X-Internal-Secret", GatlingConfig.INTERNAL_SECRET)
                            .header("Idempotency-Key", "#{idempotencyKey}")
                            .header("X-Request-Id", "#{requestId}")
                            .header("X-Trace-Id", "#{traceId}")
                            .body(StringBody(requestBody))
                            .check(status().in(200, 201))
                            .check(jsonPath("$.data.instanceNo").exists())
            );

    private final ScenarioBuilder scenario = scenario("SPI Task Dispatch")
            .exec(launchSpiTask);

    {
        setUp(
                scenario.injectOpen(
                        rampUsers(GatlingConfig.USERS_PEAK)
                                .during(GatlingConfig.RAMP_SECONDS),
                        constantUsersPerSec(GatlingConfig.USERS_PEAK)
                                .during(GatlingConfig.DURATION_SECONDS)
                )
        )
                .protocols(httpProtocol)
                .assertions(
                        global().responseTime().percentile(95).lt(GatlingConfig.WRITE_P95_MS),
                        global().failedRequests().percent().lt(GatlingConfig.MAX_ERROR_RATE_PCT)
                );
    }
}
