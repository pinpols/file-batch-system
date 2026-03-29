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
 * P1 端到端测试：Import 链路失败场景覆盖。
 *
 * <p>为什么要做失败 E2E：只测“主链路成功”无法证明系统在生产故障下可控；这里用真实 outbox→Kafka→worker→report
 * 的闭环来验证：失败时状态机落库正确、错误明细可追溯、重试/死信治理按预期工作。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li><b>场景 A</b>：未知模板（templateCode 不存在）导致失败，断言 task/job_instance 进入失败态且 error_code 非空。</li>
 *   <li><b>场景 B</b>：数据质量失败（必填字段缺失）应写入 {@code batch.file_error_record}，用于逐行问题定位。</li>
 *   <li><b>场景 C</b>：配置重试预算（FIXED + retry_max_count）后，持续失败会触发重试调度并最终进入死信。</li>
 * </ul>
 *
 * <p>说明：
 * <ul>
 *   <li>测试里显式调用 {@link E2eOutboxPublishSupport#publishAllPending(String)}，用于在 E2E profile 下驱动派发。</li>
 *   <li>重试/死信的“最终裁决”在 orchestrator 侧完成：本用例通过 DB 断言验证其结果。</li>
 * </ul>
 */
@SpringBootTest(
        classes = E2eImportApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "batch.worker.import.worker-type=IMPORT")
@ActiveProfiles({"test", "e2e"})
@Sql(scripts = {
        E2eTestSql.BIZ_SCHEMA,
        E2eTestSql.IMPORT_TEMPLATE_SEED,
})
@Tag("e2e")
class ImportFailureE2eIT extends AbstractIntegrationTest {

    private static final String TENANT = "t1";

    @Autowired
    private LaunchService launchService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private E2eOutboxPublishSupport e2eOutboxPublishSupport;

    // ── Scenario A: validation failure via unknown template ─────────────────────

    /**
     * Submits a job with a template code that does not exist in {@code file_template_config}.
     * The import worker cannot resolve the template → ParseStep / ReceiveStep fails early → task FAILED.
     * We verify:
     * <ul>
     *   <li>{@code job_task.task_status = FAILED}</li>
     *   <li>{@code job_instance.instance_status} ∈ {FAILED, PARTIAL_FAILED}</li>
     * </ul>
     */
    @Test
    void scenarioA_importFailsWhenTemplateCodeIsUnknown() {
        LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
                jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fileFormatType", "JSON");
        // Unknown template → template-config lookup returns null → pipeline fails
        params.put("templateCode", "IMP-TEMPLATE-DOES-NOT-EXIST");
        params.put("bizType", "CUSTOMER");
        params.put("content",
                "[{\"customerNo\":\"FAIL-A-001\",\"customerName\":\"Fail User A\"}]");

        launchService.launch(new LaunchRequest(
                TENANT,
                seed.jobCode(),
                LocalDate.of(2026, 1, 15),
                TriggerType.API,
                seed.requestId(),
                "e2e-tr-import-fail-a",
                params));

        e2eOutboxPublishSupport.publishAllPending(TENANT);

        // Task must reach FAILED status
        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            String status = jdbcTemplate.queryForObject(
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

        // Job instance must reflect failure
        String instanceStatus = jdbcTemplate.queryForObject(
                "select instance_status from batch.job_instance where tenant_id = ? and dedup_key = ?",
                String.class,
                TENANT,
                seed.dedupKey());
        assertThat(instanceStatus).isIn("FAILED", "PARTIAL_FAILED");

        // error_code on the task must be non-null
        String errorCode = jdbcTemplate.queryForObject(
                """
                        select t.error_code from batch.job_task t
                        join batch.job_instance ji on ji.id = t.job_instance_id
                        where ji.tenant_id = ? and ji.dedup_key = ?
                        """,
                String.class,
                TENANT,
                seed.dedupKey());
        assertThat(errorCode).isNotBlank();
    }

    // ── Scenario B: field-level validation failure via invalid record ────────────

    /**
     * Submits JSON content where a required field (customerNo) is blank.
     * With the IMP-CUSTOMER-JSON-ARRAY-STRICT template the data-quality service will flag the record.
     * The strict template uses a non-skippable validation error code, so the job fails at VALIDATE stage.
     * We verify:
     * <ul>
     *   <li>task_status = FAILED</li>
     *   <li>job_instance.instance_status ∈ {FAILED, PARTIAL_FAILED}</li>
     *   <li>at least one {@code file_error_record} row exists for the file with error_code and error_stage set</li>
     * </ul>
     *
     * Note: whether file_error_record is written depends on the skip-threshold configuration and the
     * data-quality checks registered for this template.  If the pipeline short-circuits before the
     * validate stage (e.g. at parse) the record may not be written; in that case the test falls back
     * to asserting only task/instance failure status.  The assertion for file_error_record is
     * performed conditionally to keep the test robust across configuration variants.
     */
    @Test
    void scenarioB_importFailsWhenRequiredFieldIsMissing() {
        LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
                jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fileFormatType", "JSON");
        params.put("templateCode", "IMP-CUSTOMER-JSON-ARRAY-STRICT");
        params.put("bizType", "CUSTOMER");
        // customerNo is blank → data quality should fail the record; certificateNo deliberately missing
        params.put("content",
                "[{\"customerNo\":\"\",\"customerName\":\"Bad User\",\"customerType\":\"PERSONAL\","
                        + "\"certificateNo\":\"\",\"mobileNo\":\"\",\"email\":\"\",\"status\":\"ACTIVE\"}]");

        launchService.launch(new LaunchRequest(
                TENANT,
                seed.jobCode(),
                LocalDate.of(2026, 1, 15),
                TriggerType.API,
                seed.requestId(),
                "e2e-tr-import-fail-b",
                params));

        e2eOutboxPublishSupport.publishAllPending(TENANT);

        // Task must reach FAILED status
        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            String status = jdbcTemplate.queryForObject(
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

        // Job instance failure
        String instanceStatus = jdbcTemplate.queryForObject(
                "select instance_status from batch.job_instance where tenant_id = ? and dedup_key = ?",
                String.class,
                TENANT,
                seed.dedupKey());
        assertThat(instanceStatus).isIn("FAILED", "PARTIAL_FAILED");

        // Fetch file_id associated with this job instance (may be null if pipeline aborted before file creation)
        Long fileId = jdbcTemplate.queryForObject(
                """
                        select f.id
                        from batch.file_record f
                        join batch.job_task t on t.job_instance_id = (
                            select id from batch.job_instance where tenant_id = ? and dedup_key = ?
                        )
                        where f.tenant_id = ?
                        order by f.id desc
                        limit 1
                        """,
                Long.class,
                TENANT,
                seed.dedupKey(),
                TENANT);

        assertThat(fileId).as("import should create a file_record before validate-stage failure").isNotNull();

        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            Long errCount = jdbcTemplate.queryForObject(
                    """
                            select count(*) from batch.file_error_record
                            where tenant_id = ? and file_id = ?
                            """,
                    Long.class,
                    TENANT,
                    fileId);
            assertThat(errCount).isNotNull().isGreaterThan(0L);
        });

        List<Map<String, Object>> errorRecords = jdbcTemplate.queryForList(
                """
                        select error_code, error_stage, record_no
                        from batch.file_error_record
                        where tenant_id = ? and file_id = ?
                        """,
                TENANT,
                fileId);
        assertThat(errorRecords.get(0).get("error_code")).isNotNull();
        assertThat(errorRecords.get(0).get("record_no")).isNotNull();

        // error_code on task must be non-null regardless of pipeline stage
        String errorCode = jdbcTemplate.queryForObject(
                """
                        select t.error_code from batch.job_task t
                        join batch.job_instance ji on ji.id = t.job_instance_id
                        where ji.tenant_id = ? and ji.dedup_key = ?
                        """,
                String.class,
                TENANT,
                seed.dedupKey());
        assertThat(errorCode).isNotBlank();
    }

    /**
     * Scenario C – orchestrator retry exhaustion: same persistent failure (unknown template) with a
     * non-zero retry budget must schedule partition retries and eventually emit a dead-letter row.
     */
    @Test
    void scenarioC_retryBudgetExhaustedCreatesDeadLetter() {
        LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
                jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API, "FIXED", 2);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fileFormatType", "JSON");
        params.put("templateCode", "IMP-NONEXISTENT-TEMPLATE-RETRY");
        params.put("bizType", "CUSTOMER");
        params.put("content", "[{\"customerNo\":\"R-001\",\"customerName\":\"Retry Probe\"}]");

        launchService.launch(new LaunchRequest(
                TENANT,
                seed.jobCode(),
                LocalDate.of(2026, 1, 15),
                TriggerType.API,
                seed.requestId(),
                "e2e-tr-import-fail-retry",
                params));

        e2eOutboxPublishSupport.publishAllPending(TENANT);

        await().atMost(Duration.ofSeconds(180)).pollInterval(Duration.ofMillis(400)).untilAsserted(() -> {
            e2eOutboxPublishSupport.publishAllPending(TENANT);
            Long dlq = jdbcTemplate.queryForObject(
                    """
                            select count(*) from batch.dead_letter_task dlt
                            join batch.job_partition jp on jp.id = dlt.source_id
                            join batch.job_instance ji on ji.id = jp.job_instance_id
                            where dlt.source_type = 'JOB_PARTITION'
                              and ji.tenant_id = ? and ji.dedup_key = ?
                            """,
                    Long.class,
                    TENANT,
                    seed.dedupKey());
            assertThat(dlq).isNotNull().isGreaterThanOrEqualTo(1L);
        });

        Integer partitionRetries = jdbcTemplate.queryForObject(
                """
                        select jp.retry_count from batch.job_partition jp
                        join batch.job_instance ji on ji.id = jp.job_instance_id
                        where ji.tenant_id = ? and ji.dedup_key = ?
                        """,
                Integer.class,
                TENANT,
                seed.dedupKey());
        assertThat(partitionRetries).isNotNull().isGreaterThanOrEqualTo(2);
    }
}
