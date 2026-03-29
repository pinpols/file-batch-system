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
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

/**
 * 端到端测试：Import 主链路成功闭环。
 *
 * <p>链路路径：
 * <pre>
 * API 触发 launch → orchestrator 调度/落 outbox → Kafka 派发 → import worker claim → 执行 pipeline
 *     → worker report → orchestrator 落库终态（task/partition/job_instance）
 * </pre>
 *
 * <p>断言点（典型）：
 * <ul>
 *   <li>task_status 最终为 SUCCESS</li>
 *   <li>业务表写入成功（import 的“交付结果”）</li>
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
class ImportPipelineE2eIT extends AbstractIntegrationTest {

    private static final String TENANT = "t1";

    @Autowired
    private LaunchService launchService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private E2eOutboxPublishSupport e2eOutboxPublishSupport;

    @Autowired
    @Qualifier("importBusinessDataSource")
    private DataSource businessDataSource;

    @Test
    void importJobRunsThroughKafkaClaimAndReportsSuccess() {
        LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
                jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fileFormatType", "JSON");
        params.put("templateCode", "IMP-CUSTOMER-JSON-ARRAY");
        params.put("bizType", "CUSTOMER");
        params.put("content",
                "[{\"customerNo\":\"E2E001\",\"customerName\":\"E2E User\",\"customerType\":\"PERSONAL\","
                        + "\"certificateNo\":\"ID-20260115-0001\",\"mobileNo\":\"13800000001\","
                        + "\"email\":\"e2e@example.com\",\"status\":\"ACTIVE\"}]");

        launchService.launch(new LaunchRequest(
                TENANT,
                seed.jobCode(),
                LocalDate.of(2026, 1, 15),
                TriggerType.API,
                seed.requestId(),
                "e2e-tr-import",
                params));

        e2eOutboxPublishSupport.publishAllPending(TENANT);

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
            assertThat(status).isEqualTo("SUCCESS");
        });

        JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
        Integer rows = businessJdbc.queryForObject(
                "select count(*)::int from biz.customer_account where tenant_id = ? and customer_no = ?",
                Integer.class,
                TENANT,
                "E2E001");
        assertThat(rows).isNotNull();
        assertThat(rows).isGreaterThanOrEqualTo(1);
    }
}
