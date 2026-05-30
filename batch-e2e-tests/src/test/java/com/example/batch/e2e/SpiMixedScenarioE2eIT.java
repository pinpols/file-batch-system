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
import java.util.ArrayList;
import java.util.Collections;
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
 * 仿真(testcontainers 级):一轮跑 4 类执行器混合 + 多轮重复(soak/variety),模拟真实多样负载下 SPI worker 的稳定性 —— 同一 worker
 * 进程交错处理 sql/shell/stored-proc/http,全部跑到终态 SUCCESS。
 *
 * <p>与负载测试({@link SpiTaskLoadE2eIT},单一类型高并发)互补:这里强调"类型多样 + 重复", 验证执行器注册表路由在混合流量下不串、不丢。
 */
@SpringBootTest(
    classes = E2eSpiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "e2e"})
@Sql(scripts = {E2eTestSql.BIZ_SCHEMA})
@Tag("e2e")
class SpiMixedScenarioE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final int ROUNDS = 3;

  @Autowired private LaunchService launchService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private E2eOutboxPublishSupport e2eOutboxPublishSupport;

  @Test
  void mixedExecutorTrafficAllReachesSuccess() throws IOException {
    jdbcTemplate.execute(
        "CREATE OR REPLACE PROCEDURE batch.e2e_mixed_proc() LANGUAGE plpgsql AS $$ BEGIN END; $$");

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
      String httpUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/ok";
      List<String> dedupKeys = new ArrayList<>();

      for (int round = 0; round < ROUNDS; round++) {
        for (Map<String, Object> params : mixedBatch(round, httpUrl)) {
          dedupKeys.add(launch(params, "e2e-tr-spi-mixed-" + round));
        }
      }
      e2eOutboxPublishSupport.publishAllPending(TENANT);

      int total = ROUNDS * 4;
      await()
          .atMost(Duration.ofSeconds(240))
          .pollInterval(Duration.ofMillis(500))
          .untilAsserted(() -> assertThat(succeededCount(dedupKeys)).isEqualTo(total));

      assertThat(succeededCount(dedupKeys)).isEqualTo(total);
    } finally {
      server.stop(0);
    }
  }

  // ─── helpers ─────────────────────────────────────────────────────────────────

  /** 一轮的 4 类执行器混合参数(轮次掺入 round 制造变参)。 */
  private static List<Map<String, Object>> mixedBatch(int round, String httpUrl) {
    Map<String, Object> sql = new LinkedHashMap<>();
    sql.put("taskType", "sql");
    sql.put("sql", "SELECT " + (round + 1));

    Map<String, Object> shell = new LinkedHashMap<>();
    shell.put("taskType", "shell");
    shell.put("command", "/bin/echo");
    shell.put("args", List.of("round-" + round));

    Map<String, Object> proc = new LinkedHashMap<>();
    proc.put("taskType", "stored_proc");
    proc.put("procedureName", "batch.e2e_mixed_proc");

    Map<String, Object> http = new LinkedHashMap<>();
    http.put("taskType", "http");
    http.put("url", httpUrl);
    http.put("method", "GET");

    return List.of(sql, shell, proc, http);
  }

  private String launch(Map<String, Object> params, String triggerId) {
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
            triggerId,
            params));
    return seed.dedupKey();
  }

  private int succeededCount(List<String> dedupKeys) {
    String inClause = String.join(",", Collections.nCopies(dedupKeys.size(), "?"));
    Object[] args = new Object[dedupKeys.size() + 1];
    args[0] = TENANT;
    for (int i = 0; i < dedupKeys.size(); i++) {
      args[i + 1] = dedupKeys.get(i);
    }
    Integer n =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.job_task t"
                + " join batch.job_instance ji on ji.id = t.job_instance_id"
                + " where ji.tenant_id = ? and ji.dedup_key in ("
                + inClause
                + ") and t.task_status = 'SUCCESS'",
            Integer.class,
            args);
    return n == null ? 0 : n;
  }
}
