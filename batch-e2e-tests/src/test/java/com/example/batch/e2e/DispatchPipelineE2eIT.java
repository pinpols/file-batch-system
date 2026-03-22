package com.example.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.e2e.apps.E2eDispatchApplication;
import com.example.batch.e2e.support.E2eOutboxPublishSupport;
import com.example.batch.e2e.support.E2eScenarioFixture;
import com.example.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
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
 * End-to-end: dispatch job → schedule → outbox → Kafka → dispatch worker → local channel → task report.
 */
@SpringBootTest(
        classes = E2eDispatchApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "e2e"})
@Sql(scripts = "classpath:sql/e2e-biz-schema.sql")
@Tag("e2e")
class DispatchPipelineE2eIT extends AbstractIntegrationTest {

    private static final String TENANT = "t1";

    @Autowired
    private LaunchService launchService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private E2eOutboxPublishSupport e2eOutboxPublishSupport;

    @Test
    void dispatchJobRunsThroughKafkaClaimAndReportsSuccess() {
        jdbcTemplate.update(
                """
                        insert into batch.file_channel_config (
                            tenant_id, channel_code, channel_name, channel_type, target_endpoint, auth_type,
                            config_json, receipt_policy, timeout_seconds, enabled
                        ) values (
                            ?, ?, ?, 'LOCAL', null, 'NONE',
                            jsonb_build_object(
                                'target_endpoint', '/tmp/batch-e2e-dispatch-out',
                                'receipt_policy', 'NONE',
                                'channel_type', 'LOCAL',
                                'channel_code', 'e2e_local_dispatch'
                            ),
                            'NONE', 10, true
                        )
                        on conflict (tenant_id, channel_code) do nothing
                        """,
                TENANT,
                "e2e_local_dispatch",
                "E2E local dispatch");

        String path = "/tmp/e2e-dispatch-" + System.nanoTime() + ".json";
        Long fileId = jdbcTemplate.queryForObject(
                """
                        insert into batch.file_record (
                            tenant_id, file_code, biz_type, file_category, file_name, original_file_name, file_ext,
                            file_format_type, charset, mime_type, file_size_bytes, checksum_type, storage_type,
                            storage_path, source_type, file_status, biz_date, trace_id
                        ) values (
                            ?, 'e2e-dis', 'OUTPUT', 'OUTPUT', 'e2e.json', 'e2e.json', 'json',
                            'JSON', 'UTF-8', 'application/json', 32, 'NONE', 'LOCAL',
                            ?, 'SYSTEM', 'GENERATED', date '2026-01-15', 'e2e-dis-trace'
                        ) returning id
                        """,
                Long.class,
                TENANT,
                path);
        assertThat(fileId).isNotNull();

        LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
                jdbcTemplate, TENANT, "DISPATCH", "dispatch", TriggerType.API);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fileId", String.valueOf(fileId));
        params.put("channelCode", "e2e_local_dispatch");
        params.put("dispatchTarget", "/tmp");
        params.put("externalRequestId", "e2e-ext-" + System.nanoTime());
        params.put("receiptCode", "R-E2E-DISPATCH");
        params.put("ackRequired", false);
        params.put("forceRetry", false);

        launchService.launch(new LaunchRequest(
                TENANT,
                seed.jobCode(),
                LocalDate.of(2026, 1, 15),
                TriggerType.API,
                seed.requestId(),
                "e2e-tr-dispatch",
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

        String fileStatus = jdbcTemplate.queryForObject(
                "select file_status from batch.file_record where id = ?",
                String.class,
                fileId);
        assertThat(fileStatus).isEqualTo("DISPATCHED");
    }
}
