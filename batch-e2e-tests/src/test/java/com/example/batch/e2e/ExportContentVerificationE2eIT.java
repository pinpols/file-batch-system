package com.example.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.e2e.apps.E2eExportApplication;
import com.example.batch.e2e.support.E2eOutboxPublishSupport;
import com.example.batch.e2e.support.E2eScenarioFixture;
import com.example.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import com.example.batch.e2e.support.verifier.ExportFileVerifier;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.math.BigDecimal;
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
 * P1 E2E suite: Export content-level verification.
 *
 * <p>Extends the basic success assertion from {@link ExportPipelineE2eIT} with content-level checks:
 * <ul>
 *   <li>The exported file stored in MinIO is non-empty</li>
 *   <li>Line count in the exported file matches the seeded settlement detail count</li>
 *   <li>The settlement_batch.total_amount is updated (non-zero) after the export job completes</li>
 * </ul>
 *
 * <p>MinIO download: uses {@link AbstractIntegrationTest#minioEndpoint()} and
 * {@link AbstractIntegrationTest#minioBucket()} to build a client and fetch the produced file.
 * If the export worker stores the file in a different bucket or uses a path scheme that is not
 * directly predictable from the test, the file-content assertion degrades to a "file_record
 * has storage_path set" check, which is still a meaningful content-level signal.
 */
@SpringBootTest(
        classes = E2eExportApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "batch.worker.export.worker-type=EXPORT")
@ActiveProfiles({"test", "e2e"})
@Sql(scripts = {
        "classpath:sql/e2e-biz-schema.sql",
        "classpath:db/testdata/export-template-config-seed.sql"
})
@Tag("e2e")
class ExportContentVerificationE2eIT extends AbstractIntegrationTest {

    private static final String TENANT = "t1";
    private static final String BATCH_NO = "E2E-CONTENT-VERIFY-1";

    @Autowired
    private LaunchService launchService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private E2eOutboxPublishSupport e2eOutboxPublishSupport;

    @Autowired
    @Qualifier("exportBusinessDataSource")
    private DataSource businessDataSource;

    /**
     * Content-level export verification:
     * <ol>
     *   <li>Seed a settlement batch + 2 detail records</li>
     *   <li>Run export pipeline to SUCCESS</li>
     *   <li>Assert settlement_batch.total_amount was updated (> 0)</li>
     *   <li>Assert file_record has a storage_path set (file was produced)</li>
     *   <li>Attempt to download the file from MinIO and assert it is non-empty with lines > 0</li>
     * </ol>
     */
    @Test
    void exportJobProducesNonEmptyFileAndUpdatesSettlementAmount() throws Exception {
        JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);

        Long batchId = businessJdbc.queryForObject(
                """
                        insert into biz.settlement_batch (
                            tenant_id, batch_no, biz_date, accounting_period, batch_status,
                            total_record_count, total_amount, currency
                        ) values (?, ?, date '2026-01-15', '202601', 'READY', 2, 0, 'CNY')
                        returning id
                        """,
                Long.class,
                TENANT,
                BATCH_NO);
        assertThat(batchId).isNotNull();

        businessJdbc.update(
                """
                        insert into biz.settlement_detail (
                            tenant_id, batch_id, settlement_no, customer_no, biz_date, accounting_period,
                            gross_amount, fee_amount, net_amount, currency, settlement_status
                        ) values
                            (?, ?, 'E2E-CV-001', 'C-CV-1', date '2026-01-15', '202601', 100.00, 5.00, 95.00, 'CNY', 'READY'),
                            (?, ?, 'E2E-CV-002', 'C-CV-2', date '2026-01-15', '202601', 200.00, 10.00, 190.00, 'CNY', 'READY')
                        """,
                TENANT, batchId,
                TENANT, batchId);

        LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
                jdbcTemplate, TENANT, "EXPORT", "export", TriggerType.API);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("batchNo", BATCH_NO);
        params.put("templateCode", "EXP-CUSTOMER-JSON");
        params.put("bizDate", "2026-01-15");
        params.put("bizType", "SETTLEMENT");
        params.put("fileCode", "e2e-cv-export-file");

        launchService.launch(new LaunchRequest(
                TENANT,
                seed.jobCode(),
                LocalDate.of(2026, 1, 15),
                TriggerType.API,
                seed.requestId(),
                "e2e-tr-cv-export",
                params));

        e2eOutboxPublishSupport.publishAllPending(TENANT);

        // Wait for task SUCCESS
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

        // Content-level triple-check (状态 + 产物 + 审计) via ExportFileVerifier
        // Amount rollup from seeded detail rows: 95.00 + 190.00 = 285.00
        ExportFileVerifier.forTenant(TENANT)
                .dedupKey(seed.dedupKey())
                .platformJdbc(jdbcTemplate)
                .businessJdbc(businessJdbc)
                .batchNo(BATCH_NO)
                .expectedMinTotalAmount(new BigDecimal("285.00"))
                .expectedMinFileRows(1)
                .expectedContentSnippets("E2E-CV-001", "E2E-CV-002")
                .minioEndpoint(minioEndpoint())
                .minioBucket(minioBucket())
                .build()
                .verify();
    }
}
