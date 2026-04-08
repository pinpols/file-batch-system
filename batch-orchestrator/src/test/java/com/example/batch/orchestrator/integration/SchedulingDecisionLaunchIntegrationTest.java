package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import com.example.batch.orchestrator.integration.support.LaunchIntegrationFixture.LaunchSeed;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试：{@link com.example.batch.orchestrator.scheduler.ResourceScheduler} 资源门控 ——
 * 仅当有 ONLINE 状态的 Worker 匹配 {@code worker_group} 时才派发 outbox；否则分区保持等待且不生成派发 outbox。
 */
@SpringBootTest(
        classes = BatchOrchestratorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SchedulingDecisionLaunchIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "t1";

    @Autowired
    private LaunchService launchService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldCreateOutboxWhenWorkerMatchesWorkerGroup() {
        LaunchSeed seed = LaunchIntegrationFixture.prepareLaunchWithWorker(
                jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.SCHEDULED);

        launchService.launch(new LaunchRequest(
                TENANT, seed.jobCode(), LocalDate.of(2026, 1, 15), TriggerType.SCHEDULED,
                seed.requestId(), "tr-sched", Map.of()));

        assertThat(LaunchIntegrationFixture.countOutboxByEventType(jdbcTemplate, TENANT, "IMPORT"))
                .isGreaterThanOrEqualTo(1L);
    }

    @Test
    void shouldNotWriteDispatchOutboxWhenNoOnlineWorkerForWorkerGroup() {
        long outboxBefore = LaunchIntegrationFixture.countOutboxByEventType(jdbcTemplate, TENANT, "IMPORT");
        LaunchSeed seed = LaunchIntegrationFixture.prepareLaunchWithoutWorker(
                jdbcTemplate, TENANT, "IMPORT", "NO_WORKER_GROUP_" + System.nanoTime(), TriggerType.API);

        launchService.launch(new LaunchRequest(
                TENANT, seed.jobCode(), LocalDate.of(2026, 1, 15), TriggerType.API,
                seed.requestId(), "tr-block", Map.of()));

        long outboxAfter = LaunchIntegrationFixture.countOutboxByEventType(jdbcTemplate, TENANT, "IMPORT");
        assertThat(outboxAfter - outboxBefore).isEqualTo(0L);

        Integer waiting = jdbcTemplate.queryForObject(
                """
                        select count(*)::int from batch.job_partition jp
                        join batch.job_instance ji on ji.id = jp.job_instance_id
                        where ji.tenant_id = ? and ji.dedup_key = ? and jp.partition_status = 'WAITING'
                        """,
                Integer.class,
                TENANT,
                seed.dedupKey());
        assertThat(waiting).isNotNull();
        assertThat(waiting).isGreaterThanOrEqualTo(1);
    }
}
