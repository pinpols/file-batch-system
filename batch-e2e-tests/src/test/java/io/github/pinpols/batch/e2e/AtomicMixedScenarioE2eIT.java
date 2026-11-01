package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.e2e.apps.E2eAtomicApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eOutboxPublishSupport;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 仿真(testcontainers 级):一轮跑 4 类执行器混合 + 多轮重复(soak/variety),模拟真实多样负载下 原子任务 worker 的稳定性 —— 同一 worker
 * 进程交错处理 sql/shell/stored-proc/http,全部跑到终态 SUCCESS。
 *
 * <p>与负载测试({@link AtomicTaskLoadE2eIT},单一类型高并发)互补:这里强调"类型多样 + 重复", 验证执行器注册表路由在混合流量下不串、不丢。
 */
@E2eSpringBootTest(
    classes = E2eAtomicApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@E2eBusinessSchema
@DisplayName("原子任务混合执行器端到端:同一 worker 进程交错处理数据库读写,命令行脚本,存储过程与 HTTP 调用,连续三轮投放并验证各类任务成功和实际副作用")
class AtomicMixedScenarioE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.now(ZoneOffset.UTC).plusDays(1);
  private static final int ROUNDS = 3;

  @TempDir
  private Path shellOutputDirectory;

  @Autowired
  private WorkflowRunLaunchService launchService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private E2eOutboxPublishSupport e2eOutboxPublishSupport;

  @Test
  @DisplayName("连续三轮投放四类执行器任务:验证每个任务成功及数据库、文件和 HTTP 副作用")
  void shouldReachSuccessForAllTasks_whenMixedExecutorsRunInRepeatedRounds() throws IOException {
    final String createSqlLogSql = """
        CREATE TABLE batch.e2e_mixed_sql_calls (round_no integer PRIMARY KEY)
        """;
    final String createProcedureLogSql = """
        CREATE TABLE batch.e2e_mixed_proc_calls (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY)
        """;
    final String createProcedureSql = """
        CREATE OR REPLACE PROCEDURE batch.e2e_mixed_proc() LANGUAGE plpgsql AS $$
        BEGIN INSERT INTO batch.e2e_mixed_proc_calls DEFAULT VALUES; END; $$
        """;
    jdbcTemplate.execute(createSqlLogSql);
    jdbcTemplate.execute(createProcedureLogSql);
    jdbcTemplate.execute(createProcedureSql);

    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger httpRequests = new AtomicInteger();
    server.createContext("/ok", exchange -> {
      httpRequests.incrementAndGet();
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
      Map<String, String> expectedTypes = new LinkedHashMap<>();

      for (int round = 0; round < ROUNDS; round++) {
        for (Map<String, Object> params : mixedBatch(round, httpUrl, shellOutputDirectory)) {
          String dedupKey = launch(params, "e2e-tr-atomic-mixed-" + round);
          dedupKeys.add(dedupKey);
          expectedTypes.put(dedupKey, (String) params.get("taskType"));
        }
      }
      e2eOutboxPublishSupport.publishAllPending(TENANT);

      int total = ROUNDS * 5;
      await()
          .atMost(Duration.ofSeconds(240))
          .pollInterval(Duration.ofMillis(500))
          .untilAsserted(() -> assertThat(succeededCount(dedupKeys)).isEqualTo(total));

      assertThat(succeededCount(dedupKeys)).isEqualTo(total);
      assertThat(expectedTypes).hasSize(total);
      for (Map.Entry<String, String> expected : expectedTypes.entrySet()) {
        assertTaskMatchesRequest(expected.getKey(), expected.getValue());
      }
      assertThat(httpRequests.get()).as("每轮 HTTP 任务应被接收一次").isEqualTo(ROUNDS);
      final String countProcedureCallsSql = "SELECT count(*) FROM batch.e2e_mixed_proc_calls";
      Integer procedureCalls = jdbcTemplate.queryForObject(countProcedureCallsSql, Integer.class);
      assertThat(procedureCalls).as("存储过程应实际执行三次").isEqualTo(ROUNDS);
      final String selectSqlRounds =
          "SELECT round_no FROM batch.e2e_mixed_sql_calls ORDER BY round_no";
      assertThat(jdbcTemplate.queryForList(selectSqlRounds, Integer.class))
          .as("SQL 写入任务应分别提交三轮业务记录")
          .containsExactly(0, 1, 2);
      for (int round = 0; round < ROUNDS; round++) {
        assertThat(Files.isRegularFile(shellOutputDirectory.resolve("round-" + round)))
            .as("Shell 任务应创建本轮临时文件 %s", round)
            .isTrue();
      }
    } finally {
      server.stop(0);
    }
  }

  // ─── helpers ─────────────────────────────────────────────────────────────────

  /** 一轮的 4 类执行器混合参数(轮次掺入 round 制造变参)。 */
  private static List<Map<String, Object>> mixedBatch(
      int round, String httpUrl, Path shellDirectory) {
    Map<String, Object> sql = new LinkedHashMap<>();
    sql.put("taskType", "sql");
    sql.put("sql", "SELECT %d".formatted(round + 1));

    Map<String, Object> sqlWrite = new LinkedHashMap<>();
    sqlWrite.put("taskType", "sql");
    sqlWrite.put(
        "sql", "INSERT INTO batch.e2e_mixed_sql_calls (round_no) VALUES (%d)".formatted(round));

    Map<String, Object> shell = new LinkedHashMap<>();
    shell.put("taskType", "shell");
    shell.put("command", "/usr/bin/touch");
    shell.put("args", List.of(shellDirectory.resolve("round-" + round).toString()));

    Map<String, Object> proc = new LinkedHashMap<>();
    proc.put("taskType", "stored_proc");
    proc.put("procedureName", "batch.e2e_mixed_proc");

    Map<String, Object> http = new LinkedHashMap<>();
    http.put("taskType", "http");
    http.put("url", httpUrl);
    http.put("method", "GET");

    return List.of(sql, sqlWrite, shell, proc, http);
  }

  private String launch(Map<String, Object> params, String triggerId) {
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "ATOMIC", "atomic", TriggerType.API, BIZ_DATE);
    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        BIZ_DATE,
        TriggerType.API,
        seed.requestId(),
        triggerId,
        params));
    return seed.dedupKey();
  }

  private void assertTaskMatchesRequest(String dedupKey, String expectedType) {
    final String selectTaskSql = """
        select t.task_status, t.task_payload ->> 'taskType' as executor_type
        from batch.job_task t
        join batch.job_instance ji on ji.tenant_id = t.tenant_id and ji.id = t.job_instance_id
        where ji.tenant_id = ? and ji.dedup_key = ?
        """;
    List<Map<String, Object>> tasks = jdbcTemplate.queryForList(selectTaskSql, TENANT, dedupKey);
    assertThat(tasks).as("每个请求对应一个任务：%s", dedupKey).hasSize(1);
    assertThat(tasks.getFirst())
        .containsEntry("task_status", "SUCCESS")
        .containsEntry("executor_type", expectedType);
  }

  private int succeededCount(List<String> dedupKeys) {
    String inClause = String.join(",", Collections.nCopies(dedupKeys.size(), "?"));
    Object[] args = new Object[dedupKeys.size() + 1];
    args[0] = TENANT;
    for (int i = 0; i < dedupKeys.size(); i++) {
      args[i + 1] = dedupKeys.get(i);
    }
    final String selectCountSql = """
        select count(*) from batch.job_task t join batch.job_instance ji on ji.id = t.job_instance_id\s\
        where ji.tenant_id = ? and ji.dedup_key in (%s) and t.task_status = 'SUCCESS'\
        """.formatted(inClause);
    Integer n = jdbcTemplate.queryForObject(selectCountSql, Integer.class, args);
    return n == null ? 0 : n;
  }
}
