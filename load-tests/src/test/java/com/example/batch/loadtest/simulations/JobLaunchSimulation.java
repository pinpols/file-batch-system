package com.example.batch.loadtest.simulations;

import com.example.batch.loadtest.GatlingConfig;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Measures the throughput and latency of the job-launch path:
 *   POST /api/triggers/launch  (batch-trigger:8081)
 *
 * <p>Load profile: ramp from 1 to {@code users.peak} over {@code ramp.seconds},
 * sustain for {@code duration.seconds}.
 *
 * <p>SLO assertions:
 * <ul>
 *   <li>p95 response time &lt; {@code slo.write.p95ms} (default 500 ms)</li>
 *   <li>error rate &lt; {@code slo.maxErrorPct} (default 1 %)</li>
 * </ul>
 *
 * <p>Run:
 * <pre>
 *   mvn gatling:test -Dsimulation=JobLaunchSimulation \
 *       -DjobCode=E2E_IMPORT_LOAD -DtenantId=t1 \
 *       -Dusers.peak=50 -Dduration.seconds=180
 * </pre>
 */
public class JobLaunchSimulation extends Simulation {

    // ── Protocol ───────────────────────────────────────────────────────────────

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(GatlingConfig.TRIGGER_BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .shareConnections();

    // ── Feeder: generates a unique idempotency key per request ─────────────────

    private final Iterator<Map<String, Object>> feeder = Stream.generate(() ->
            Map.<String, Object>of(
                    "idempotencyKey", UUID.randomUUID().toString(),
                    "requestId", UUID.randomUUID().toString(),
                    "traceId", "lt-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            )
    ).iterator();

    // ── Request body ───────────────────────────────────────────────────────────

    private final String requestBody = """
            {
              "tenantId": "%s",
              "jobCode": "%s",
              "bizDate": "%s",
              "triggerType": "API",
              "params": {}
            }
            """.formatted(GatlingConfig.TENANT_ID, GatlingConfig.JOB_CODE, GatlingConfig.BIZ_DATE);

    // ── Scenario ───────────────────────────────────────────────────────────────

    private final ChainBuilder launchJob = feed(feeder)
            .exec(
                    http("POST /api/triggers/launch")
                            .post("/api/triggers/launch")
                            .header("Idempotency-Key", "#{idempotencyKey}")
                            .header("X-Request-Id", "#{requestId}")
                            .header("X-Trace-Id", "#{traceId}")
                            .body(StringBody(requestBody))
                            .check(status().in(200, 201))
                            .check(jsonPath("$.data.instanceNo").exists())
            );

    private final ScenarioBuilder scenario = scenario("Job Launch")
            .exec(launchJob);

    // ── Load profile ───────────────────────────────────────────────────────────

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
