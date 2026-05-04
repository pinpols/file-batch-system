package com.example.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.e2e.apps.E2eImportApplication;
import com.example.batch.e2e.support.E2eScenarioFixture;
import com.example.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import com.example.batch.e2e.support.E2eStatusLogger;
import com.example.batch.e2e.support.E2eTestSql;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

/**
 * 端到端测试：验证真实的 Outbox 轮询器会自动投递消息（不依赖测试手工 publish）。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li>覆盖生产形态：由 {@code OutboxPollScheduler} 定时扫描 outbox 表并投递到 Kafka。
 *   <li>避免“测试里手动 publishAllPending()”掩盖 outbox forwarder 没跑/配置错误的风险。
 * </ul>
 *
 * <p>实现方式：把 {@code batch.outbox.poll-interval-millis} 调小到 500ms，并且<strong>刻意不调用</strong> {@code
 * E2eOutboxPublishSupport.publishAllPending()}，仅等待最终任务成功。
 */
@SpringBootTest(
    classes = E2eImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.worker.import.worker-type=IMPORT",
      "batch.outbox.poll-interval-millis=500",
      "batch.outbox.min-poll-interval-millis=500"
    })
@ActiveProfiles({"test", "e2e"})
@Sql(
    scripts = {
      E2eTestSql.BIZ_SCHEMA,
      E2eTestSql.IMPORT_TEMPLATE_SEED,
    })
@Tag("e2e")
class OutboxForwarderE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";

  @Autowired private LaunchService launchService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void outboxSchedulerAutomaticallyPublishesAndWorkerReportsSuccess() {
    LaunchSeed seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileFormatType", "JSON");
    params.put("templateCode", "IMP-CUSTOMER-JSON-ARRAY");
    params.put("bizType", "CUSTOMER");
    params.put(
        "content",
        "[{\"customerNo\":\"E2E-FWD-001\",\"customerName\":\"Forwarder"
            + " User\",\"customerType\":\"PERSONAL\","
            + "\"certificateNo\":\"ID-20260115-9001\",\"mobileNo\":\"13800009001\","
            + "\"email\":\"fwd@example.com\",\"status\":\"ACTIVE\"}]");

    launchService.launch(
        new LaunchRequest(
            TENANT,
            seed.jobCode(),
            LocalDate.of(2026, 1, 15),
            TriggerType.API,
            seed.requestId(),
            "e2e-tr-outbox-fwd",
            params));

    // OutboxPollScheduler fires every 500 ms and publishes the pending outbox event automatically.
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              E2eStatusLogger.logJobFlowSnapshot(
                  jdbcTemplate, TENANT, seed.dedupKey(), "OutboxForwarderE2eIT");
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
