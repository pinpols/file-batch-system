package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.application.service.TaskExecutionService;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;
import com.example.batch.orchestrator.domain.value.JsonbString;
import com.example.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import com.example.batch.orchestrator.integration.support.LaunchIntegrationFixture.LaunchSeed;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.repository.WorkerRegistryRepository;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration test: two workers race to claim the same launched task.
 *
 * <p>Proves the real claim path only admits one winner under concurrency. The loser observes the
 * winner's assignment when it re-reads the row, which is the expected conflict behavior.
 */
@SpringBootTest(
        classes = BatchOrchestratorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ConcurrentTaskClaimIntegrationTest extends AbstractIntegrationTest {

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
    private JobPartitionMapper jobPartitionMapper;

    @Autowired
    private WorkerRegistryRepository workerRegistryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void assignWorker_onlyOneWorkerWins_whenTwoWorkersRaceConcurrently() throws Exception {
        LaunchSeed seed = LaunchIntegrationFixture.prepareLaunchWithWorker(
                jdbcTemplate, TENANT, "IMPORT", "DEFAULT", TriggerType.MANUAL);
        String winnerWorker = seed.workerCode();
        String loserWorker = "worker-race-b";
        workerRegistryRepository.save(onlineWorker(TENANT, loserWorker, "DEFAULT"));

        LaunchResponse response = launchService.launch(new LaunchRequest(
                TENANT,
                seed.jobCode(),
                BIZ_DATE,
                TriggerType.MANUAL,
                seed.requestId(),
                "trace-concurrent-claim",
                Map.of()));
        assertThat(response.instanceNo()).isNotBlank();

        JobInstanceEntity jobInstance = jobInstanceMapper.selectByTenantAndDedupKey(TENANT, seed.dedupKey());
        assertThat(jobInstance).isNotNull();

        List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(
                new com.example.batch.orchestrator.domain.query.JobTaskQuery(TENANT, jobInstance.getId(), null, null, null));
        assertThat(tasks).hasSize(1);
        JobTaskEntity task = tasks.get(0);
        Long taskId = task.getId();

        List<JobPartitionEntity> partitions = jobPartitionMapper.selectByQuery(
                new com.example.batch.orchestrator.domain.query.JobPartitionQuery(TENANT, jobInstance.getId(), null, null));
        assertThat(partitions).hasSize(1);
        Long partitionId = partitions.get(0).getId();

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<JobTaskEntity> winnerFuture = pool.submit(() -> {
            startGate.await();
            return taskExecutionService.assignWorker(TENANT, taskId, winnerWorker);
        });
        Future<JobTaskEntity> loserFuture = pool.submit(() -> {
            startGate.await();
            return taskExecutionService.assignWorker(TENANT, taskId, loserWorker);
        });

        startGate.countDown();

        JobTaskEntity winnerResult = winnerFuture.get();
        JobTaskEntity loserResult = loserFuture.get();
        pool.shutdownNow();

        assertThat(winnerResult).isNotNull();
        assertThat(loserResult).isNotNull();
        assertThat(
                (winnerWorker.equals(winnerResult.getAssignedWorkerCode()) ? 1 : 0)
                        + (loserWorker.equals(loserResult.getAssignedWorkerCode()) ? 1 : 0))
                .as("exactly one caller should see itself as the claim winner")
                .isEqualTo(1);

        JobTaskEntity finalTask = jobTaskMapper.selectById(TENANT, taskId);
        assertThat(finalTask.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
        assertThat(finalTask.getAssignedWorkerCode()).isIn(winnerWorker, loserWorker);

        JobPartitionEntity finalPartition = jobPartitionMapper.selectById(TENANT, partitionId);
        assertThat(finalPartition.getPartitionStatus()).isEqualTo("RUNNING");
        assertThat(finalPartition.getWorkerCode()).isEqualTo(finalTask.getAssignedWorkerCode());
    }

    private static WorkerRegistryRecord onlineWorker(String tenantId, String workerCode, String workerGroup) {
        return new WorkerRegistryRecord(
                null, tenantId, workerCode, workerGroup,
                new JsonbString("{}"), null,
                "ONLINE",
                Instant.now(), 0, null, null);
    }
}
