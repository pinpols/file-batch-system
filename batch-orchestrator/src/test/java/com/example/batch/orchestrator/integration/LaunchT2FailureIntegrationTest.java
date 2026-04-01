package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.application.service.PartitionDispatchService;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import com.example.batch.orchestrator.integration.support.LaunchIntegrationFixture.LaunchSeed;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Integration test for the T1/T2 transaction split in {@link LaunchService#launch}.
 *
 * <p>Scenario: T2 ({@link PartitionDispatchService#dispatch}) throws after T1
 * ({@code prepareJobInstance}) has already committed. Verifies that:
 * <ol>
 *   <li>The {@code job_instance} row written by T1 survives the T2 failure.</li>
 *   <li>No {@code job_partition} rows exist (T2 rolled back entirely).</li>
 *   <li>A subsequent {@code launch()} with the same {@code requestId} hits the dedup path
 *       and returns the already-created instance without calling T2 again.</li>
 * </ol>
 */
@SpringBootTest(
        classes = BatchOrchestratorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LaunchT2FailureIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "t1";
    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 1, 15);

    @Autowired
    private LaunchService launchService;

    @Autowired
    private JobInstanceMapper jobInstanceMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private PartitionDispatchService partitionDispatchService;

    @Test
    void t1CommitsAndDedupWorksWhenT2Throws() {
        LaunchSeed seed = LaunchIntegrationFixture.prepareLaunchWithWorker(
                jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.MANUAL);

        doThrow(new RuntimeException("simulated T2 failure"))
                .when(partitionDispatchService)
                .dispatch(any());

        LaunchRequest request = new LaunchRequest(
                TENANT, seed.jobCode(), BIZ_DATE, TriggerType.MANUAL,
                seed.requestId(), "trace-t2-failure-test", Map.of());

        // First call: T2 fails — exception propagates out of launch()
        assertThatThrownBy(() -> launchService.launch(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated T2 failure");

        // T1 committed: job_instance must exist in DB
        JobInstanceEntity jobInstance = jobInstanceMapper.selectByTenantAndDedupKey(TENANT, seed.dedupKey());
        assertThat(jobInstance)
                .as("job_instance should be committed by T1 even though T2 failed")
                .isNotNull();

        // T2 rolled back: no job_partition rows for this instance
        Long partitionCount = jdbcTemplate.queryForObject(
                "select count(*) from batch.job_partition where job_instance_id = ?",
                Long.class,
                jobInstance.getId());
        assertThat(partitionCount)
                .as("no job_partition rows should exist when T2 rolled back")
                .isZero();

        // T2 rolled back: no job_task rows for this instance
        Long taskCount = jdbcTemplate.queryForObject(
                "select count(*) from batch.job_task where job_instance_id = ?",
                Long.class,
                jobInstance.getId());
        assertThat(taskCount)
                .as("no job_task rows should exist when T2 rolled back")
                .isZero();

        // Second call with same requestId: dedup path — T2 is never called again
        LaunchResponse retryResponse = launchService.launch(request);
        assertThat(retryResponse.instanceNo())
                .as("retry should return the instance created by the first T1")
                .isEqualTo(jobInstance.getInstanceNo());
    }
}
