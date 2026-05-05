package com.example.batch.loadtest.simulations;

import com.example.batch.loadtest.GatlingConfig;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Synthetic worker lifecycle pressure for orchestrator task APIs:
 * {@code CLAIM -> optional pause -> REPORT}.
 *
 * <p>Input is an explicit CSV file with header {@code taskId,tenantId,workerId}. Use only with
 * isolated READY tasks that are not consumed by real workers; this scenario mutates task and
 * partition state.
 */
public class WorkerTaskLifecycleSimulation extends Simulation {

  private final HttpProtocolBuilder orchestratorProtocol =
      http
          .baseUrl(GatlingConfig.ORCHESTRATOR_BASE_URL)
          .acceptHeader("application/json")
          .contentTypeHeader("application/json")
          .header("X-Internal-Secret", GatlingConfig.INTERNAL_SECRET)
          .shareConnections();

  private final Iterator<Map<String, Object>> taskFeeder = taskFeeder();

  private static Iterator<Map<String, Object>> taskFeeder() {
    Path csv = Path.of(GatlingConfig.TASK_LIFECYCLE_CSV);
    if (!Files.isRegularFile(csv)) {
      throw new IllegalArgumentException(
          "task lifecycle CSV not found: "
              + csv.toAbsolutePath()
              + " (expected header: taskId,tenantId,workerId)");
    }
    try {
      List<Map<String, Object>> rows =
          Files.readAllLines(csv).stream()
              .skip(1)
              .map(String::trim)
              .filter(line -> !line.isEmpty() && !line.startsWith("#"))
              .map(
                  line -> {
                    String[] parts = line.split(",", -1);
                    if (parts.length < 3) {
                      throw new IllegalArgumentException(
                          "bad task lifecycle CSV row, expected taskId,tenantId,workerId: " + line);
                    }
                    return Map.<String, Object>of(
                        "taskId", parts[0].trim(),
                        "tenantId", parts[1].trim(),
                        "workerId", parts[2].trim(),
                        "traceId",
                            "wtl-"
                                + UUID.randomUUID()
                                    .toString()
                                    .replace("-", "")
                                    .substring(0, 16));
                  })
              .toList();
      if (rows.isEmpty()) {
        throw new IllegalArgumentException("task lifecycle CSV has no data rows: " + csv);
      }
      return rows.iterator();
    } catch (IOException ex) {
      throw new IllegalArgumentException("failed to read task lifecycle CSV: " + csv, ex);
    }
  }

  private final ChainBuilder claimThenReport =
      feed(taskFeeder)
          .exec(
              http("POST /internal/tasks/{taskId}/claim")
                  .post("/internal/tasks/#{taskId}/claim")
                  .header("X-Tenant-Id", "#{tenantId}")
                  .header("X-Trace-Id", "#{traceId}")
                  .body(
                      StringBody(
                          """
                          {
                            "tenantId": "#{tenantId}",
                            "workerId": "#{workerId}"
                          }
                          """))
                  .check(status().is(200))
                  .check(jsonPath("$.partitionInvocationId").optional().saveAs("partitionInvocationId")))
          .pause(Duration.ofMillis(GatlingConfig.TASK_LIFECYCLE_EXECUTE_PAUSE_MS))
          .exec(
              http("POST /internal/tasks/{taskId}/report")
                  .post("/internal/tasks/#{taskId}/report")
                  .header("X-Tenant-Id", "#{tenantId}")
                  .header("X-Trace-Id", "#{traceId}")
                  .body(
                      StringBody(
                          session -> {
                            String invocation = session.getString("partitionInvocationId");
                            String invocationJson =
                                invocation == null
                                    ? "null"
                                    : "\"" + invocation.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
                            return """
                                {
                                  "taskId": %s,
                                  "tenantId": "%s",
                                  "workerId": "%s",
                                  "traceId": "%s",
                                  "success": true,
                                  "code": "SUCCESS",
                                  "message": "synthetic lifecycle load test",
                                  "resultSummary": "{\\"synthetic\\":true}",
                                  "partitionInvocationId": %s
                                }
                                """
                                .formatted(
                                    session.getString("taskId"),
                                    session.getString("tenantId"),
                                    session.getString("workerId"),
                                    session.getString("traceId"),
                                    invocationJson);
                          }))
                  .check(status().in(200, 204)));

  private final ScenarioBuilder scenario =
      scenario("Worker task lifecycle CLAIM -> REPORT").exec(claimThenReport);

  {
    setUp(
            scenario.injectOpen(
                rampUsers(GatlingConfig.USERS_PEAK).during(GatlingConfig.RAMP_SECONDS)))
        .protocols(orchestratorProtocol)
        .maxDuration(Duration.ofSeconds(GatlingConfig.RAMP_SECONDS + 120L))
        .assertions(
            details("POST /internal/tasks/{taskId}/claim")
                .responseTime()
                .percentile(95)
                .lt(GatlingConfig.WRITE_P95_MS),
            details("POST /internal/tasks/{taskId}/report")
                .responseTime()
                .percentile(95)
                .lt(GatlingConfig.WRITE_P95_MS),
            global().failedRequests().percent().lt(GatlingConfig.MAX_ERROR_RATE_PCT));
  }
}
