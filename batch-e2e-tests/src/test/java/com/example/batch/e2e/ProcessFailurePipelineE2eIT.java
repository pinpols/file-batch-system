package com.example.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.e2e.apps.E2eProcessApplication;
import com.example.batch.e2e.support.E2eOutboxPublishSupport;
import com.example.batch.e2e.support.E2eScenarioFixture;
import com.example.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import com.example.batch.e2e.support.E2eStatusLogger;
import com.example.batch.e2e.support.E2eTestSql;
import com.example.batch.e2e.support.ProcessE2eFixture;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 端到端测试：PROCESS 失败路径（VALIDATE 阶段用户校验规则不通过）。
 *
 * <p>测试意图：当 sqlTransformCompute 里配置的 validations 失败时，COMMIT 不执行，target 表保持空，staging 仍保留供
 * forensics；任务/实例终态为 FAILED。
 */
@SpringBootTest(
    classes = E2eProcessApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "batch.worker.process.worker-type=PROCESS")
@ActiveProfiles({"test", "e2e"})
@Sql(scripts = {E2eTestSql.BIZ_SCHEMA})
@Tag("e2e")
class ProcessFailurePipelineE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";

  @Autowired private LaunchService launchService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private E2eOutboxPublishSupport e2eOutboxPublishSupport;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void wap_sqlTransform_validationFailureAbortsCommit() throws Exception {
    LaunchSeed seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, TENANT, "PROCESS", "process", TriggerType.API);
    ProcessE2eFixture.cleanProcessRows(jdbcTemplate);
    ProcessE2eFixture.seedDemoOrderEvents(jdbcTemplate, TENANT);
    // 用户校验规则：要求所有行 total_amount >= 1000（本批数据是 30/7，所以会失败）
    Map<String, Object> validationRule =
        Map.of(
            "name",
            "min_total_threshold",
            "checkSql",
            "select bool_and((payload->>'total_amount')::numeric >= 1000) AS pass,"
                + " 'total_amount must be >= 1000' AS message"
                + " from batch.process_staging where batch_key = :batchKey");
    ProcessE2eFixture.seedSqlTransformPipelineDefinition(
        jdbcTemplate, objectMapper, TENANT, seed.jobCode(), List.of(validationRule));

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("bizDate", "2026-01-15");

    launchService.launch(
        new LaunchRequest(
            TENANT,
            seed.jobCode(),
            LocalDate.of(2026, 1, 15),
            TriggerType.API,
            seed.requestId(),
            "e2e-tr-process-validate-fail",
            params));
    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              E2eStatusLogger.logJobFlowSnapshot(
                  jdbcTemplate, TENANT, seed.dedupKey(), "ProcessE2e-validate-fail");
              Map<String, Object> outcome = loadProcessOutcome(seed.dedupKey());
              assertThat(outcome.get("task_status")).isEqualTo("FAILED");
              assertThat(outcome.get("instance_status")).isEqualTo("FAILED");
            });

    // Target 表保持空（COMMIT 没跑）
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*)::int from biz.process_account_summary", Integer.class))
        .isZero();
    // staging 仍含本批的 2 行（留 forensics，直到下次 prepare 才清）
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*)::int from batch.process_staging where tenant_id=?",
                Integer.class,
                TENANT))
        .isEqualTo(2);
  }

  private Map<String, Object> loadProcessOutcome(String dedupKey) {
    return jdbcTemplate.queryForMap(
        """
        select t.task_status, ji.instance_status, ji.high_water_mark_out
        from batch.job_task t
        join batch.job_instance ji on ji.id = t.job_instance_id
        where ji.tenant_id = ? and ji.dedup_key = ?
        """,
        TENANT,
        dedupKey);
  }
}
