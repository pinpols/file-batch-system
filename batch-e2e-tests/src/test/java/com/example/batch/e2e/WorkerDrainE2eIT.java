package com.example.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.e2e.apps.E2eImportApplication;
import com.example.batch.e2e.support.E2eScenarioFixture;
import com.example.batch.e2e.support.E2eTestSql;
import com.example.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import com.example.batch.orchestrator.application.service.WorkerDrainGovernanceService;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest(
        classes = E2eImportApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "batch.worker.import.worker-type=IMPORT",
                "batch.worker.import.worker-code=e2e-import-drain-1",
                "batch.worker.drain.enabled=true",
                "batch.worker.drain.check-interval-millis=600000"
        })
@ActiveProfiles({"test", "e2e"})
@Sql(scripts = {
        E2eTestSql.BIZ_SCHEMA,
        E2eTestSql.IMPORT_TEMPLATE_SEED,
})
@Tag("e2e")
class WorkerDrainE2eIT extends AbstractIntegrationTest {

    private static final String TENANT = "t1";
    private static final String WORKER_CODE = "e2e-import-drain-1";
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @Autowired
    private LaunchService launchService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorkerDrainGovernanceService workerDrainGovernanceService;

    @Value("${local.server.port}")
    private int localServerPort;

    @AfterEach
    void cleanupWorkerRegistry() {
        jdbcTemplate.update(
                "delete from batch.worker_registry where tenant_id = ? and worker_code = ?",
                TENANT,
                WORKER_CODE);
    }

    @Test
    void drainTimeoutReclaimsRunningTaskAndDecommissionsWorker() throws Exception {
        LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
                jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fileFormatType", "JSON");
        params.put("templateCode", "IMP-CUSTOMER-JSON-ARRAY");
        params.put("bizType", "CUSTOMER");
        params.put("content",
                "[{\"customerNo\":\"DRAIN-E2E-001\",\"customerName\":\"Drain User\",\"customerType\":\"PERSONAL\","
                        + "\"certificateNo\":\"ID-20260115-DRN1\",\"mobileNo\":\"13800009999\","
                        + "\"email\":\"drain@example.com\",\"status\":\"ACTIVE\"}]");

        launchService.launch(new LaunchRequest(
                TENANT,
                seed.jobCode(),
                LocalDate.of(2026, 1, 15),
                TriggerType.API,
                seed.requestId(),
                "e2e-tr-worker-drain",
                params));

        Long taskId = jdbcTemplate.queryForObject(
                """
                        select t.id
                        from batch.job_task t
                                 join batch.job_instance ji on ji.id = t.job_instance_id
                        where ji.tenant_id = ?
                          and ji.dedup_key = ?
                        order by t.id asc
                        limit 1
                        """,
                Long.class,
                TENANT,
                seed.dedupKey());
        assertThat(taskId).isNotNull();

        jdbcTemplate.update(
                """
                        update batch.job_task
                        set task_status = 'RUNNING',
                            assigned_worker_code = ?,
                            started_at = ?,
                            updated_at = current_timestamp
                        where tenant_id = ? and id = ?
                        """,
                WORKER_CODE,
                Timestamp.from(Instant.now().minusSeconds(600)),
                TENANT,
                taskId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + localServerPort + "/internal/workers/" + WORKER_CODE + "/drain"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"tenantId\":\"" + TENANT + "\",\"timeoutSeconds\":1}"))
                .build();
        HttpResponse<String> drainResponse = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(drainResponse.statusCode()).isBetween(200, 299);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Map<String, Object> worker = jdbcTemplate.queryForMap(
                    """
                            select status, drain_started_at, drain_deadline_at
                            from batch.worker_registry
                            where tenant_id = ? and worker_code = ?
                            """,
                    TENANT,
                    WORKER_CODE);
            assertThat(worker.get("status")).isEqualTo("DRAINING");
            assertThat(worker.get("drain_started_at")).isNotNull();
            assertThat(worker.get("drain_deadline_at")).isNotNull();
        });

        jdbcTemplate.update(
                """
                        update batch.worker_registry
                        set drain_deadline_at = current_timestamp - interval '1 second',
                            updated_at = current_timestamp
                        where tenant_id = ? and worker_code = ?
                        """,
                TENANT,
                WORKER_CODE);
        workerDrainGovernanceService.takeoverAfterDrainTimeout(TENANT, WORKER_CODE);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Map<String, Object> workerAfterTimeout = jdbcTemplate.queryForMap(
                    """
                            select status, drain_started_at, drain_deadline_at
                            from batch.worker_registry
                            where tenant_id = ? and worker_code = ?
                            """,
                    TENANT,
                    WORKER_CODE);
            assertThat(workerAfterTimeout.get("status")).isEqualTo("DECOMMISSIONED");
            assertThat(workerAfterTimeout.get("drain_started_at")).isNull();
            assertThat(workerAfterTimeout.get("drain_deadline_at")).isNull();
        });

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Map<String, Object> taskAfterTimeout = jdbcTemplate.queryForMap(
                    """
                            select task_status, assigned_worker_code
                            from batch.job_task
                            where id = ?
                            """,
                    taskId);
            assertThat(taskAfterTimeout.get("task_status")).isEqualTo("READY");
            assertThat(taskAfterTimeout.get("assigned_worker_code")).isNull();
        });
    }
}
