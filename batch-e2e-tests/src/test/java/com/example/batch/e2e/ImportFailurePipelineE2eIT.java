package com.example.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.e2e.apps.E2eImportApplication;
import com.example.batch.e2e.support.E2eOutboxPublishSupport;
import com.example.batch.e2e.support.E2eScenarioFixture;
import com.example.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
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
 * 端到端测试：Import 解析失败（不可解析 JSON）场景。
 *
 * <p>测试意图：验证 worker 在 PARSE 阶段捕获解析异常并回报失败，orchestrator 侧把 task/job_instance 落到失败态。
 *
 * <p>关键点：
 *
 * <ul>
 *   <li>输入是“不是 JSON 的字符串”，用于稳定触发 ParseStep 的失败分支。
 *   <li>此用例不启用重试（retry_policy=NONE），目的是验证“失败直接收敛为终态”的最短路径。
 * </ul>
 */
@SpringBootTest(
    classes = E2eImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "batch.worker.import.worker-type=IMPORT")
@ActiveProfiles({"test", "e2e"})
@Sql(
    scripts = {
      E2eTestSql.BIZ_SCHEMA,
      E2eTestSql.IMPORT_TEMPLATE_SEED,
    })
@Tag("e2e")
class ImportFailurePipelineE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";

  @Autowired private LaunchService launchService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private E2eOutboxPublishSupport e2eOutboxPublishSupport;

  @Test
  void importJobReportsFailedWhenContentIsUnparseable() {
    LaunchSeed seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileFormatType", "JSON");
    params.put("templateCode", "IMP-CUSTOMER-JSON-ARRAY");
    params.put("bizType", "CUSTOMER");
    // Intentionally invalid JSON — ParseStep will throw and return IMPORT_PARSE_FAILED
    params.put("content", "THIS_IS_NOT_VALID_JSON_CONTENT");

    launchService.launch(
        new LaunchRequest(
            TENANT,
            seed.jobCode(),
            LocalDate.of(2026, 1, 15),
            TriggerType.API,
            seed.requestId(),
            "e2e-tr-import-fail",
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

    // Verify job_instance also reflects the failure
    String instanceStatus =
        jdbcTemplate.queryForObject(
            "select instance_status from batch.job_instance where tenant_id = ? and dedup_key = ?",
            String.class,
            TENANT,
            seed.dedupKey());
    assertThat(instanceStatus).isIn("FAILED", "PARTIAL_FAILED");
  }
}
