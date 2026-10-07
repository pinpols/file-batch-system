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
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

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
@E2eBusinessSchema
@Tag("e2e")
@DisplayName("真实种子作业的原子任务端到端: 执行器协议预置在作业定义默认参数中, 空参触发后经参数合成, 事件投递与专属派发交由对应子执行器执行, 任务终态落库为成功")
class AtomicSeedStrictVerifyE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";

  @Autowired
  private LaunchService launchService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private E2eOutboxPublishSupport e2eOutboxPublishSupport;

  @Test
  @DisplayName("sql 类型种子作业: 定义内预置查询协议, 空参触发后经事件投递与专属派发执行, 任务终态落库为成功")
  void shouldRunSqlSeedTask_whenLaunchCarriesNoParams() {
    runSeedJob("{\"taskType\":\"sql\",\"sql\":\"SELECT 1\"}");
  }

  @Test
  @DisplayName("shell 类型种子作业: 定义内预置命令与入参, 空参触发后经专属派发执行, 任务终态落库为成功")
  void shouldRunShellSeedTask_whenLaunchCarriesNoParams() {
    runSeedJob("{\"taskType\":\"shell\",\"command\":\"/bin/echo\",\"args\":[\"hello-from-spi\"]}");
  }

  @Test
  @DisplayName("存储过程类型种子作业: 先建真实过程再以定义内协议空参触发, 经专属派发调用后任务终态落库为成功")
  void shouldRunStoredProcSeedTask_whenLaunchCarriesNoParams() {
    jdbcTemplate.execute(
        "CREATE OR REPLACE PROCEDURE batch.e2e_seed_proc() LANGUAGE plpgsql AS $$ BEGIN END; $$");
    runSeedJob("{\"taskType\":\"stored_proc\",\"procedureName\":\"batch.e2e_seed_proc\"}");
  }

  @Test
  @DisplayName("http 类型种子作业: 定义内预置请求地址与方法, 空参触发后经专属派发请求本地服务, 任务终态落库为成功")
  void shouldRunHttpSeedTask_whenLaunchCarriesNoParams() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/ok", exchange -> {
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
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "ATOMIC", "atomic", TriggerType.API);

    // 把执行器协议写进 job_definition.default_params(生产形态:管理员配好,调度只给 jobCode)
    jdbcTemplate.update(
        "update batch.job_definition set default_params = ?::jsonb"
            + " where tenant_id = ? and job_code = ?",
        defaultParamsJson,
        TENANT,
        seed.jobCode());

    launchService.launch(new LaunchRequest(
        TENANT,
        seed.jobCode(),
        LocalDate.of(2026, 1, 15),
        TriggerType.API,
        seed.requestId(),
        "e2e-tr-atomic-seed",
        Map.of())); // 不传任何 launch 参数 —— 协议全来自 default_params

    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String status = jdbcTemplate.queryForObject("""
                      select t.task_status from batch.job_task t
                      join batch.job_instance ji on ji.id = t.job_instance_id
                      where ji.tenant_id = ? and ji.dedup_key = ?
                      """, String.class, TENANT, seed.dedupKey());
          assertThat(status).isEqualTo("SUCCESS");
        });
  }
}
