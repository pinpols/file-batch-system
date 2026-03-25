package com.example.batch.loadtest.simulations;

import com.example.batch.loadtest.GatlingConfig;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Measures the query-side throughput and latency of the console API:
 *   GET /api/console/query/instances        (job instance list)
 *   GET /api/console/query/workers          (worker registry)
 *   GET /api/console/query/alerts           (alert events)
 *   GET /actuator/health                    (health probe baseline)
 *
 * <p>Load profile: constant {@code users.peak} users for {@code duration.seconds}.
 *
 * <p>SLO assertions:
 * <ul>
 *   <li>p99 response time &lt; {@code slo.read.p99ms} (default 300 ms)</li>
 *   <li>error rate &lt; 0.1 %</li>
 * </ul>
 *
 * <p>Run:
 * <pre>
 *   mvn gatling:test -Dsimulation=ConsoleQuerySimulation \
 *       -DtenantId=t1 -Dusers.peak=30 -Dduration.seconds=180
 * </pre>
 *
 * <p>NOTE: The console API requires authentication. Set the Authorization header
 * via {@code -Dconsole.authToken=Bearer <token>}.
 */
public class ConsoleQuerySimulation extends Simulation {

    private static final String AUTH_TOKEN =
            System.getProperty("console.authToken", "Bearer load-test-token");

    // ── Protocol ───────────────────────────────────────────────────────────────

    private final HttpProtocolBuilder http = http()
            .baseUrl(GatlingConfig.CONSOLE_BASE_URL)
            .acceptHeader("application/json")
            .header("Authorization", AUTH_TOKEN)
            .shareConnections();

    // ── Request chain: weighted mix of typical console queries ─────────────────

    private final ChainBuilder queries = exec(
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
            .pause(1)
            .exec(
                    http("GET /api/console/query/alerts")
                            .get("/api/console/query/alerts")
                            .queryParam("tenantId", GatlingConfig.TENANT_ID)
                            .queryParam("limit", "10")
                            .check(status().is(200))
            )
            .pause(1)
            .exec(
                    http("GET /actuator/health")
                            .get("/actuator/health")
                            .check(status().is(200))
            );

    private final ScenarioBuilder scenario = scenario("Console Query")
            .forever().exec(queries);

    // ── Load profile ───────────────────────────────────────────────────────────

    {
        setUp(
                scenario.injectOpen(
                        constantUsersPerSec(GatlingConfig.USERS_PEAK)
                                .during(GatlingConfig.DURATION_SECONDS)
                )
        )
                .protocols(http)
                .assertions(
                        global().responseTime().percentile(99).lt(GatlingConfig.READ_P99_MS),
                        global().failedRequests().percent().lt(0.1)
                );
    }
}
