package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.e2e.apps.E2eExportApplication;
import io.github.pinpols.batch.e2e.support.E2eOutboxPublishSupport;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
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
 * 端到端测试：Export 模板不存在导致失败。
 *
 * <p>测试意图：验证“配置缺失/引用错误”这类常见问题能被快速失败并正确写入数据库，而不是卡在中间态。
 *
 * <p>断言点：
 *
 * <ul>
 *   <li>task_status 最终为 FAILED
 *   <li>job_instance.instance_status 最终为 FAILED 或 PARTIAL_FAILED（按分片汇总口径）
 * </ul>
 */
@SpringBootTest(
    classes = E2eExportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "batch.worker.export.worker-type=EXPORT")
@ActiveProfiles({"test", "e2e"})
@Sql(
    scripts = {
      E2eTestSql.BIZ_SCHEMA,
    })
@Tag("e2e")
class ExportFailurePipelineE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";

  @Autowired private LaunchService launchService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private E2eOutboxPublishSupport e2eOutboxPublishSupport;

  @Test
  void exportJobReportsFailedWhenTemplateDoesNotExist() {
    LaunchSeed seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, TENANT, "EXPORT", "export", TriggerType.API);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("batchNo", "E2E-FAIL-BATCH-001");
    // Non-existent template — export worker fails at template resolution
    params.put("templateCode", "EXP-TEMPLATE-DOES-NOT-EXIST");
    params.put("bizDate", "2026-01-15");
    params.put("bizType", "SETTLEMENT");
    params.put("fileCode", "e2e-export-fail-file");

    launchService.launch(
        new LaunchRequest(
            TENANT,
            seed.jobCode(),
            LocalDate.of(2026, 1, 15),
            TriggerType.API,
            seed.requestId(),
            "e2e-tr-export-fail",
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
              assertThat(status).isEqualTo("FAILED");
            });

    String instanceStatus =
        jdbcTemplate.queryForObject(
            "select instance_status from batch.job_instance where tenant_id = ? and dedup_key = ?",
            String.class,
            TENANT,
            seed.dedupKey());
    assertThat(instanceStatus).isIn("FAILED", "PARTIAL_FAILED");
  }
}
