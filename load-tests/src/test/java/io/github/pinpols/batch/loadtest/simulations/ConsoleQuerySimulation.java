package io.github.pinpols.batch.loadtest.simulations;

import io.github.pinpols.batch.loadtest.GatlingConfig;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * 测量 Console API 查询侧的吞吐量和延迟：
 *   GET /api/console/queries/instances        (job instance list)
 *   GET /api/console/queries/workers          (worker registry)
 *   GET /api/console/queries/alerts           (alert events)
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
 * <p>Console 查询需要登录 Cookie，通过 {@code -Dconsole.accessToken=<token>} 提供登录凭据。
 */
public class ConsoleQuerySimulation extends Simulation {

    // ── Protocol ───────────────────────────────────────────────────────────────

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(GatlingConfig.CONSOLE_BASE_URL)
            .acceptHeader("application/json")
            .shareConnections();

    // ── 请求链：按权重混合典型 Console 查询 ─────────────────

    private final ChainBuilder queries = exec(
            http("GET /api/console/query/instances")
                    .get("/api/console/queries/instances")
                    .queryParam("tenantId", GatlingConfig.TENANT_ID)
                    .queryParam("pageNo", "1")
                    .queryParam("pageSize", "20")
                    .check(status().is(200))
    )
            .pause(1)
            .exec(
                    http("GET /api/console/query/workers")
                            .get("/api/console/queries/workers")
                            .queryParam("tenantId", GatlingConfig.TENANT_ID)
                            .check(status().is(200))
            )
            .pause(1)
            .exec(
                    http("GET /api/console/query/alerts")
                            .get("/api/console/queries/alerts")
                            .queryParam("tenantId", GatlingConfig.TENANT_ID)
                            .queryParam("pageNo", "1")
                            .queryParam("pageSize", "10")
                            .check(status().is(200))
            )
            .pause(1)
            .exec(
                    http("GET /actuator/health")
                            .get("/actuator/health")
                            .check(status().is(200))
            );

    private final ScenarioBuilder scenario = scenario("Console Query")
            .exec(addCookie(GatlingConfig.consoleAuthCookie()))
            .forever().on(queries);

    // ── Load profile ───────────────────────────────────────────────────────────

    {
        setUp(
                scenario.injectOpen(
                        constantUsersPerSec(GatlingConfig.USERS_PEAK)
                                .during(GatlingConfig.DURATION_SECONDS)
                )
        )
                .protocols(httpProtocol)
                .maxDuration(Duration.ofSeconds(GatlingConfig.DURATION_SECONDS + 30L))
                .assertions(
                        global().responseTime().percentile(99).lt(GatlingConfig.READ_P99_MS),
                        global().failedRequests().percent().lt(0.1)
                );
    }
}
