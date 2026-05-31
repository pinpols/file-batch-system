package com.example.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.e2e.apps.E2eAtomicApplication;
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
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

/**
 * 真实数据严格验证(testcontainers 级):用 <b>生产形态</b> 的 SPI job 定义跑真链 —— 执行器协议(taskType + 参数) 放在 {@code
 * job_definition.default_params}(对齐 scripts/db/test-seed/platform_seed.sql 里的 atomic_*_demo),
 * launch 时 <b>不传任何参数</b>,验证 default_params → effectiveParams → payload → 子执行器 这条真实生产路径成立。
 *
 * <p>与 {@link AtomicTaskPipelineE2eIT}(参数走 LaunchRequest)互补:这里覆盖"管理员在 job 定义里配好协议、调度只给 jobCode"
 * 的真实使用方式,是严格(真实数据,非合成入参)验证。
 */
@SpringBootTest(
    classes = E2eAtomicApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "e2e"})
@Sql(scripts = {E2eTestSql.BIZ_SCHEMA})
@Tag("e2e")
class AtomicSeedStrictVerifyE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";

  @Autowired private LaunchService launchService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private E2eOutboxPublishSupport e2eOutboxPublishSupport;

  @Test
  void sqlSeedJobRunsFromDefaultParams() {
    runSeedJob("{\"taskType\":\"sql\",\"sql\":\"SELECT 1\"}");
  }

  @Test
  void shellSeedJobRunsFromDefaultParams() {
    runSeedJob("{\"taskType\":\"shell\",\"command\":\"/bin/echo\",\"args\":[\"hello-from-spi\"]}");
  }

  @Test
  void storedProcSeedJobRunsFromDefaultParams() {
    jdbcTemplate.execute(
        "CREATE OR REPLACE PROCEDURE batch.e2e_seed_proc() LANGUAGE plpgsql AS $$ BEGIN END; $$");
    runSeedJob("{\"taskType\":\"stored_proc\",\"procedureName\":\"batch.e2e_seed_proc\"}");
  }

  @Test
  void httpSeedJobRunsFromDefaultParams() throws IOException {
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
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/ok";
      runSeedJob("{\"taskType\":\"http\",\"url\":\"" + url + "\",\"method\":\"GET\"}");
    } finally {
      server.stop(0);
    }
  }

  // ─── helpers ─────────────────────────────────────────────────────────────────

  /** 建 SPI job(default_params 携带执行器协议)→ launch(空参)→ 等终态 SUCCESS。 */
  private void runSeedJob(String defaultParamsJson) {
    LaunchSeed seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, TENANT, "ATOMIC", "atomic", TriggerType.API);

    // 把执行器协议写进 job_definition.default_params(生产形态:管理员配好,调度只给 jobCode)
    jdbcTemplate.update(
        "update batch.job_definition set default_params = ?::jsonb"
            + " where tenant_id = ? and job_code = ?",
        defaultParamsJson,
        TENANT,
        seed.jobCode());

    launchService.launch(
        new LaunchRequest(
            TENANT,
            seed.jobCode(),
            LocalDate.of(2026, 1, 15),
            TriggerType.API,
            seed.requestId(),
            "e2e-tr-spi-seed",
            Map.of())); // 不传任何 launch 参数 —— 协议全来自 default_params

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
