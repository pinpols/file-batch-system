package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.application.service.TaskExecutionService;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.query.JobPartitionQuery;
import com.example.batch.orchestrator.domain.query.JobTaskQuery;
import com.example.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import com.example.batch.orchestrator.integration.support.LaunchIntegrationFixture.LaunchSeed;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
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
 * Integration test: worker claim → lease renew → progress report → complete.
 *
 * <p>Exercises the full worker-side interaction:
 * <ol>
 *   <li>Worker calls <em>claim</em> ({@link TaskExecutionService#assignWorker}) — task moves to RUNNING.</li>
 *   <li>Worker calls <em>renew</em> ({@link TaskExecutionService#renewTaskLease}) — lease is extended.</li>
 *   <li>Worker calls <em>report success</em> ({@link TaskExecutionService#applyTaskOutcome}) — task reaches SUCCESS.</li>
 *   <li>Partition and job_instance both reach SUCCESS terminal state.</li>
 * </ol>
 *
 * <p>Also verifies that a second claim attempt by a different worker is rejected (conflict semantics).
 */
@SpringBootTest(
        classes = BatchOrchestratorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WorkerClaimProgressCompleteIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "t1";
    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 1, 15);

    @Autowired
    private LaunchService launchService;

    @Autowired
    private TaskExecutionService taskExecutionService;

    @Autowired
    private JobInstanceMapper jobInstanceMapper;

    @Autowired
    private JobPartitionMapper jobPartitionMapper;

    @Autowired
    private JobTaskMapper jobTaskMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void worker_claim_renewLease_reportSuccess_taskAndPartitionAndInstanceReachSuccess() {
        LaunchSeed seed = LaunchIntegrationFixture.prepareLaunchWithWorker(
                jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);

        // 1) Launch
        LaunchResponse response = launchService.launch(new LaunchRequest(
                TENANT, seed.jobCode(), BIZ_DATE, TriggerType.API,
                seed.requestId(), "trace-wk-" + seed.requestId(), Map.of()));
        assertThat(response.instanceNo()).isNotBlank();

        JobInstanceEntity jobInstance = jobInstanceMapper.selectByTenantAndDedupKey(TENANT, seed.dedupKey());
        assertThat(jobInstance).isNotNull();

        List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(
                new JobTaskQuery(TENANT, jobInstance.getId(), null, null, null));
        assertThat(tasks).isNotEmpty();
        JobTaskEntity task = tasks.get(0);

        List<JobPartitionEntity> partitions = jobPartitionMapper.selectByQuery(
                new JobPartitionQuery(TENANT, jobInstance.getId(), null, null));
        assertThat(partitions).isNotEmpty();
        JobPartitionEntity partition = partitions.get(0);

        // 2) Worker claims the task
        JobTaskEntity claimed = taskExecutionService.assignWorker(TENANT, task.getId(), seed.workerCode());
        assertThat(claimed).isNotNull();
        assertThat(claimed.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
        assertThat(claimed.getAssignedWorkerCode()).isEqualTo(seed.workerCode());

        // Partition should also reflect RUNNING
        JobPartitionEntity runningPartition = jobPartitionMapper.selectById(TENANT, partition.getId());
        assertThat(runningPartition.getPartitionStatus()).isEqualTo(PartitionStatus.RUNNING.code());

        // 3) Worker renews lease (simulates heartbeat / progress ping)
        boolean renewed = taskExecutionService.renewTaskLease(TENANT, task.getId(), seed.workerCode());
        assertThat(renewed).isTrue();

        // A different worker should not be able to steal the lease
        boolean stolenByRogue = taskExecutionService.renewTaskLease(TENANT, task.getId(), "rogue-worker");
        assertThat(stolenByRogue).isFalse();

        // 4) Worker reports success (progress complete)
        taskExecutionService.applyTaskOutcome(new TaskOutcomeCommand(
                TENANT, task.getId(), true, "{\"records\":100,\"status\":\"processed\"}", null, null));

        // 5) Verify task SUCCESS
        JobTaskEntity finishedTask = jobTaskMapper.selectById(TENANT, task.getId());
        assertThat(finishedTask.getTaskStatus()).isEqualTo(TaskStatus.SUCCESS.code());

        // 6) Verify partition SUCCESS
        JobPartitionEntity finishedPartition = jobPartitionMapper.selectById(TENANT, partition.getId());
        assertThat(finishedPartition.getPartitionStatus()).isEqualTo(PartitionStatus.SUCCESS.code());

        // 7) Verify job_instance SUCCESS
        JobInstanceEntity finishedInstance = jobInstanceMapper.selectById(TENANT, jobInstance.getId());
        assertThat(finishedInstance.getInstanceStatus()).isEqualTo(JobInstanceStatus.SUCCESS.code());
    }

    @Test
    void secondWorkerClaim_afterFirstClaim_returnsSameTask_notASecondRunning() {
        LaunchSeed seed = LaunchIntegrationFixture.prepareLaunchWithWorker(
                jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);

        launchService.launch(new LaunchRequest(
                TENANT, seed.jobCode(), BIZ_DATE, TriggerType.API,
                seed.requestId(), "trace-wk2-" + seed.requestId(), Map.of()));

        JobInstanceEntity jobInstance = jobInstanceMapper.selectByTenantAndDedupKey(TENANT, seed.dedupKey());
        List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(
                new JobTaskQuery(TENANT, jobInstance.getId(), null, null, null));
        assertThat(tasks).isNotEmpty();
        JobTaskEntity task = tasks.get(0);

        // First claim succeeds
        JobTaskEntity first = taskExecutionService.assignWorker(TENANT, task.getId(), seed.workerCode());
        assertThat(first.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());

        // Second claim by a different worker: should return the task row but the worker code
        // should reflect the first claimer (no overwrite)
        JobTaskEntity second = taskExecutionService.assignWorker(TENANT, task.getId(), "other-worker");
        assertThat(second).isNotNull();
        assertThat(second.getAssignedWorkerCode()).isEqualTo(seed.workerCode());
    }
}
