package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import com.example.batch.orchestrator.integration.support.LaunchIntegrationFixture.LaunchSeed;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration: each {@link TriggerType} produces a {@code job_instance} with matching {@code trigger_type}
 * and dispatches at least one outbox row when a worker exists (schedule-plan path).
 */
@SpringBootTest(
        classes = BatchOrchestratorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TriggerTypeLaunchIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "t1";

    @Autowired
    private LaunchService launchService;

    @Autowired
    private JobInstanceMapper jobInstanceMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @EnumSource(TriggerType.class)
    void shouldLaunchAndPersistTriggerTypeForEachTriggerType(TriggerType triggerType) {
        LaunchSeed seed = LaunchIntegrationFixture.prepareLaunchWithWorker(
                jdbcTemplate, TENANT, "DISPATCH", "DISPATCH", triggerType);

        LaunchResponse response = launchService.launch(new LaunchRequest(
                TENANT,
                seed.jobCode(),
                LocalDate.of(2026, 1, 15),
                triggerType,
                seed.requestId(),
                "trace-" + seed.requestId(),
                Map.of()));

        assertThat(response.instanceNo()).isNotBlank();

        JobInstanceEntity ji = jobInstanceMapper.selectByTenantAndDedupKey(TENANT, seed.dedupKey());
        assertThat(ji).isNotNull();
        assertThat(ji.getTriggerType()).isEqualTo(triggerType.code());

        assertThat(LaunchIntegrationFixture.countOutboxByEventType(jdbcTemplate, TENANT, "IMPORT"))
                .isGreaterThanOrEqualTo(1L);
    }
}
