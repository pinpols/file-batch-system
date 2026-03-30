package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.application.service.TaskExecutionService;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.query.JobTaskQuery;
import com.example.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import com.example.batch.orchestrator.integration.support.LaunchIntegrationFixture.LaunchSeed;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration test: job launch → task claim → task report (success) → job_instance reaches SUCCESS.
 *
 * <p>Exercises the full synchronous lifecycle chain:
 * <ol>
 *   <li>{@link LaunchService#launch} creates job_instance + partitions + tasks + outbox_event.</li>
 *   <li>{@link TaskExecutionService#assignWorker} transitions the task to RUNNING.</li>
 *   <li>{@link TaskExecutionService#applyTaskOutcome} with success=true promotes the task and
 *       eventually the job_instance to SUCCESS.</li>
 * </ol>
 */
@SpringBootTest(
        classes = BatchOrchestratorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JobLaunchToFinishLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "t1";
    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 1, 15);

    @Autowired
    private LaunchService launchService;

    @Autowired
    private TaskExecutionService taskExecutionService;

    @Autowired
    private JobInstanceMapper jobInstanceMapper;

    @Autowired
    private JobTaskMapper jobTaskMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void launchThenClaimThenReport_jobInstanceReachesSuccess() {
        LaunchSeed seed = LaunchIntegrationFixture.prepareLaunchWithWorker(
                jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);

        // 1) Launch
        LaunchResponse response = launchService.launch(new LaunchRequest(
                TENANT, seed.jobCode(), BIZ_DATE, TriggerType.API,
                seed.requestId(), "trace-lifecycle-" + seed.requestId(), Map.of()));

        assertThat(response.instanceNo()).isNotBlank();

        JobInstanceEntity jobInstance = jobInstanceMapper.selectByTenantAndDedupKey(TENANT, seed.dedupKey());
        assertThat(jobInstance).isNotNull();
        assertThat(jobInstance.getInstanceStatus()).isIn(
                JobInstanceStatus.READY.code(),
                JobInstanceStatus.RUNNING.code(),
                JobInstanceStatus.WAITING.code());

        // 2) Claim the task
        List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(
                new JobTaskQuery(TENANT, jobInstance.getId(), null, null, null));
        assertThat(tasks).isNotEmpty();
        JobTaskEntity task = tasks.get(0);

        JobTaskEntity claimed = taskExecutionService.assignWorker(TENANT, task.getId(), seed.workerCode());
        assertThat(claimed).isNotNull();
        assertThat(claimed.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
        assertThat(claimed.getAssignedWorkerCode()).isEqualTo(seed.workerCode());

        // 3) Report success
        taskExecutionService.applyTaskOutcome(new TaskOutcomeCommand(
                TENANT, task.getId(), true, "{\"status\":\"processed ok\"}", null, null));

        // 4) Verify final task status
        JobTaskEntity finishedTask = jobTaskMapper.selectById(TENANT, task.getId());
        assertThat(finishedTask.getTaskStatus()).isEqualTo(TaskStatus.SUCCESS.code());

        // 5) Verify job_instance reaches SUCCESS
        JobInstanceEntity finishedInstance = jobInstanceMapper.selectById(TENANT, jobInstance.getId());
        assertThat(finishedInstance.getInstanceStatus()).isEqualTo(JobInstanceStatus.SUCCESS.code());
    }

    @Test
    void launchThenClaimThenReport_failureTransitionsTaskToFailed() {
        LaunchSeed seed = LaunchIntegrationFixture.prepareLaunchWithWorker(
                jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);

        LaunchResponse response = launchService.launch(new LaunchRequest(
                TENANT, seed.jobCode(), BIZ_DATE, TriggerType.API,
                seed.requestId(), "trace-fail-" + seed.requestId(), Map.of()));

        assertThat(response.instanceNo()).isNotBlank();

        JobInstanceEntity jobInstance = jobInstanceMapper.selectByTenantAndDedupKey(TENANT, seed.dedupKey());
        assertThat(jobInstance).isNotNull();

        List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(
                new JobTaskQuery(TENANT, jobInstance.getId(), null, null, null));
        assertThat(tasks).isNotEmpty();
        JobTaskEntity task = tasks.get(0);

        taskExecutionService.assignWorker(TENANT, task.getId(), seed.workerCode());

        // Report failure (no retry policy configured in the fixture: retry_max_count = 0)
        taskExecutionService.applyTaskOutcome(new TaskOutcomeCommand(
                TENANT, task.getId(), false, null, "TEST_FAILURE", "simulated error"));

        JobTaskEntity finishedTask = jobTaskMapper.selectById(TENANT, task.getId());
        assertThat(finishedTask.getTaskStatus()).isEqualTo(TaskStatus.FAILED.code());

        JobInstanceEntity finishedInstance = jobInstanceMapper.selectById(TENANT, jobInstance.getId());
        assertThat(finishedInstance.getInstanceStatus()).isEqualTo(JobInstanceStatus.FAILED.code());
    }
}
