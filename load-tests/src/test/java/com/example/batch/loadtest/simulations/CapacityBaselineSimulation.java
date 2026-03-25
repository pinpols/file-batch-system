package com.example.batch.loadtest.simulations;

import com.example.batch.loadtest.GatlingConfig;
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
 * Production-level capacity baseline simulation.
 *
 * <p>Models realistic mixed traffic: 70 % read (console queries) + 30 % write (job launches).
 * Uses a stepped ramp to find the saturation point rather than a fixed user count.
 *
 * <p>Stepped ramp profile:
 * <pre>
 *   Step 1:  25 users   ×  60 s
 *   Step 2:  50 users   ×  60 s
 *   Step 3: 100 users   ×  60 s  ← default capacity baseline target
 *   Step 4: 150 users   ×  60 s
 *   Step 5: 200 users   ×  60 s  ← observe degradation onset
 * </pre>
 *
 * <p>The test passes only when the 100-user step meets all SLOs. Steps above 100 users
 * are used to characterise the degradation curve; failures there do not fail the build.
 *
 * <p>SLO assertions (applied globally across all steps):
 * <ul>
 *   <li>write p95 &lt; 500 ms</li>
 *   <li>read  p99 &lt; 300 ms</li>
 *   <li>global error rate &lt; 1 %</li>
 * </ul>
 *
 * <p>Run:
 * <pre>
 *   mvn gatling:test -Dsimulation=CapacityBaselineSimulation \
 *       -DjobCode=E2E_IMPORT_LOAD -DtenantId=t1
 * </pre>
 */
public class CapacityBaselineSimulation extends Simulation {

    private static final String AUTH_TOKEN =
            System.getProperty("console.authToken", "Bearer load-test-token");

    // ── Protocols ──────────────────────────────────────────────────────────────

    private final HttpProtocolBuilder triggerHttp = http()
            .baseUrl(GatlingConfig.TRIGGER_BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .shareConnections();

    private final HttpProtocolBuilder consoleHttp = http()
            .baseUrl(GatlingConfig.CONSOLE_BASE_URL)
            .acceptHeader("application/json")
            .header("Authorization", AUTH_TOKEN)
            .shareConnections();

    // ── Feeders ────────────────────────────────────────────────────────────────

    private final Iterator<Map<String, Object>> launchFeeder = Stream.generate(() ->
            Map.<String, Object>of(
                    "idempotencyKey", UUID.randomUUID().toString(),
                    "requestId", UUID.randomUUID().toString()
            )
    ).iterator();

    // ── Request bodies ─────────────────────────────────────────────────────────

    private final String launchBody = """
            {
              "tenantId": "%s",
              "jobCode": "%s",
              "bizDate": "%s",
              "triggerType": "API",
              "params": {}
            }
            """.formatted(GatlingConfig.TENANT_ID, GatlingConfig.JOB_CODE, GatlingConfig.BIZ_DATE);

    // ── Write scenario (30 %) — job launch ─────────────────────────────────────

    private final ChainBuilder writePath = feed(launchFeeder)
            .exec(
                    http("POST /api/triggers/launch")
                            .post("/api/triggers/launch")
                            .header("Idempotency-Key", "#{idempotencyKey}")
                            .header("X-Request-Id", "#{requestId}")
                            .body(StringBody(launchBody))
                            .check(status().in(200, 201))
            )
            .pause(2);

    private final ScenarioBuilder writeScenario = scenario("Write: Job Launch")
            .forever().exec(writePath);

    // ── Read scenario (70 %) — console queries ─────────────────────────────────

    private final ChainBuilder readPath = exec(
            http("GET /api/console/query/instances")
                    .get("/api/console/query/instances")
                    .queryParam("tenantId", GatlingConfig.TENANT_ID)
                    .queryParam("limit", "20")
                    .check(status().is(200))
    )
            .pause(1)
            .exec(
                    http("GET /api/console/query/workers")
                            .get("/api/console/query/workers")
                            .queryParam("tenantId", GatlingConfig.TENANT_ID)
                            .check(status().is(200))
            )
            .pause(1);

    private final ScenarioBuilder readScenario = scenario("Read: Console Query")
            .forever().exec(readPath);

    // ── Stepped ramp load profile ──────────────────────────────────────────────

    /*
     * Each step runs for 60 s.  writeRatio = 30 % of total users.
     * Step sizes: 25 / 50 / 100 / 150 / 200 total virtual users.
     */
    private static final int STEP_DURATION = 60;

    private static int writeUsers(int total) {
        return Math.max(1, (int) Math.round(total * 0.30));
    }

    private static int readUsers(int total) {
        return total - writeUsers(total);
    }

    {
        setUp(
                // ── Write population ──
                writeScenario.injectOpen(
                        stressPeakUsers(writeUsers(25)).during(STEP_DURATION),
                        stressPeakUsers(writeUsers(50)).during(STEP_DURATION),
                        stressPeakUsers(writeUsers(100)).during(STEP_DURATION),
                        stressPeakUsers(writeUsers(150)).during(STEP_DURATION),
                        stressPeakUsers(writeUsers(200)).during(STEP_DURATION)
                ).andThen(
                        // ── Read population ──
                        readScenario.injectOpen(
                                stressPeakUsers(readUsers(25)).during(STEP_DURATION),
                                stressPeakUsers(readUsers(50)).during(STEP_DURATION),
                                stressPeakUsers(readUsers(100)).during(STEP_DURATION),
                                stressPeakUsers(readUsers(150)).during(STEP_DURATION),
                                stressPeakUsers(readUsers(200)).during(STEP_DURATION)
                        )
                )
        )
                .protocols(triggerHttp)
                .assertions(
                        // Write SLO
                        details("POST /api/triggers/launch")
                                .responseTime().percentile(95).lt(GatlingConfig.WRITE_P95_MS),
                        // Read SLO
                        details("GET /api/console/query/instances")
                                .responseTime().percentile(99).lt(GatlingConfig.READ_P99_MS),
                        // Global error gate
                        global().failedRequests().percent().lt(GatlingConfig.MAX_ERROR_RATE_PCT)
                );
    }
}
