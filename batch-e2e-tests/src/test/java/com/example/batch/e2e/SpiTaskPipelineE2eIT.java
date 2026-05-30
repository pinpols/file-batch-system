package com.example.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.e2e.apps.E2eSpiApplication;
import com.example.batch.e2e.support.E2eOutboxPublishSupport;
import com.example.batch.e2e.support.E2eScenarioFixture;
import com.example.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import com.example.batch.e2e.support.E2eTestSql;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

/**
 * 端到端测试:专用 SPI worker 四类原子任务主链路成功闭环(ADR-029)。
 *
 * <p>链路路径(以 sql 为例):
 *
 * <pre>
 * launch(job_type=SPI, params={taskType:&lt;type&gt;, ...})
 *   → orchestrator 建 task/outbox(task_type=SPI,payload 带子协议)
 *   → Kafka 派发到 batch.task.dispatch.spi
 *   → SPI worker(只扫 worker.spi,无 pipeline adapter)claim
 *   → DefaultStepExecutionAdapter 按 payload.taskType 路由到对应执行器执行
 *   → report → orchestrator 终态 SUCCESS
 * </pre>
 *
 * <p>四个执行器(sql/shell/stored-proc/http)各跑一条真实全链,验证派发拓扑(SPI 专属 topic)+ 子执行器路由(payload.taskType)+ 参数透传
 * + 终态闭环。
 */
@SpringBootTest(
    classes = E2eSpiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "e2e"})
@Sql(
    scripts = {
      E2eTestSql.BIZ_SCHEMA,
    })
@Tag("e2e")
class SpiTaskPipelineE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";

  @Autowired private LaunchService launchService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private E2eOutboxPublishSupport e2eOutboxPublishSupport;

  @Test
  void sqlTaskRunsThroughKafkaAndReportsSuccess() {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("taskType", "sql");
    params.put("sql", "SELECT 1");

    launchAndAwaitSuccess(params);
  }

  @Test
  void shellTaskRunsThroughKafkaAndReportsSuccess() {
    // shell 走 adapter 的 payload.taskType 路由 + RCE 最敏感执行器,真链路只跑无害的 /bin/echo。
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("taskType", "shell");
    params.put("command", "/bin/echo");
    params.put("args", List.of("hello-from-spi-e2e"));

    launchAndAwaitSuccess(params);
  }

  @Test
  void storedProcTaskRunsThroughKafkaAndReportsSuccess() {
    // 真 PROCEDURE:executor 经 pg_proc.prokind 判定后发原生 CALL(PG 默认 {call}→SELECT 调真过程会报错)。
    jdbcTemplate.execute(
        "CREATE OR REPLACE PROCEDURE batch.e2e_spi_proc() LANGUAGE plpgsql AS $$ BEGIN END; $$");

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("taskType", "stored_proc");
    params.put("procedureName", "batch.e2e_spi_proc");

    launchAndAwaitSuccess(params);
  }

  @Test
  void httpTaskRunsThroughKafkaAndReportsSuccess() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/ok",
        exchange -> {
          byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });
    server.start();
    try {
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("taskType", "http");
      params.put("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/ok");
      params.put("method", "GET");

      launchAndAwaitSuccess(params);
    } finally {
      server.stop(0);
    }
  }

  // ─── helpers ─────────────────────────────────────────────────────────────────

  /** 起一个 job_type=SPI 的任务,把 params 作为 payload 透传,等终态 SUCCESS。 */
  private void launchAndAwaitSuccess(Map<String, Object> params) {
    LaunchSeed seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, TENANT, "SPI", "spi", TriggerType.API);

    launchService.launch(
        new LaunchRequest(
            TENANT,
            seed.jobCode(),
            LocalDate.of(2026, 1, 15),
            TriggerType.API,
            seed.requestId(),
            "e2e-tr-spi",
            params));

    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              String status =
                  jdbcTemplate.queryForObject(
                      """
                      select t.task_status from batch.job_task t
                      join batch.job_instance ji on ji.id = t.job_instance_id
                      where ji.tenant_id = ? and ji.dedup_key = ?
                      """,
                      String.class,
                      TENANT,
                      seed.dedupKey());
              assertThat(status).isEqualTo("SUCCESS");
            });
  }
}
