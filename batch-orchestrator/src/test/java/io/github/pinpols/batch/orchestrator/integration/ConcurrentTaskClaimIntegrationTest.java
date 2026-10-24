package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskExecutionService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobPartitionQuery;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.domain.value.JsonbString;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.WorkerRegistryCache;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.orchestrator.integration.support.WorkerRegistryCacheTestSupport;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试：两个 Worker 竞争认领同一个已启动的任务。
 *
 * <p>验证在并发条件下真实认领路径只允许一个获胜者。失败方在重新读取行时 观察到获胜方的分配结果，这是预期的冲突行为。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("真实数据库下任务并发认领的唯一胜出者保证与分区归属一致性")
class ConcurrentTaskClaimIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, Month.JANUARY, 15);

  @Autowired
  private WorkflowRunLaunchService workflowRunLaunchService;

  @Autowired
  private TaskExecutionService taskExecutionService;

  @Autowired
  private JobInstanceMapper jobInstanceMapper;

  @Autowired
  private JobTaskMapper jobTaskMapper;

  @Autowired
  private JobPartitionMapper jobPartitionMapper;

  @Autowired
  private WorkerRegistryMapper workerRegistryMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private WorkerRegistryCache workerRegistryCache;

  @BeforeEach
  void refreshWorkersForClaim() {
    WorkerRegistryCacheTestSupport.evictTenantWorkerSelectors(workerRegistryCache, TENANT);
    LaunchIntegrationFixture.refreshAssignableWorkersForTenant(jdbcTemplate, TENANT);
  }

  @Test
  @DisplayName("两个执行器同时认领同一任务时数据库只落一个胜出者,分区状态与归属同步收敛")
  void assignWorker_onlyOneWorkerWins_whenTwoWorkersRaceConcurrently() throws Exception {
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, TENANT, "IMPORT", "DEFAULT", TriggerType.MANUAL);
    String winnerWorker = seed.workerCode();
    String loserWorker = "worker-race-b";
    workerRegistryMapper.saveLikeSdj(onlineWorker(TENANT, loserWorker, "DEFAULT"));

    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.MANUAL)
        .requestId(seed.requestId())
        .traceId("trace-concurrent-claim")
        .params(Map.of())
        .build();
    LaunchResponse response = workflowRunLaunchService.launch(launchRequest);
    assertThat(response.instanceNo()).isNotBlank();

    JobInstanceEntity jobInstance = jobInstanceForWorkflowRun(response);
    assertThat(jobInstance).isNotNull();

    List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(
        new JobTaskQuery(TENANT, jobInstance.getId(), null, null, null));
    assertThat(tasks).hasSize(1);
    JobTaskEntity task = tasks.get(0);
    Long taskId = task.getId();

    List<JobPartitionEntity> partitions = jobPartitionMapper.selectByQuery(
        new JobPartitionQuery(TENANT, jobInstance.getId(), null, null));
    assertThat(partitions).hasSize(1);
    Long partitionId = partitions.get(0).getId();

    LaunchIntegrationFixture.refreshAssignableWorkersForTenant(jdbcTemplate, TENANT);

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

    assertConcurrentClaimRpcAndDbAgree(
        TENANT, taskId, partitionId, winnerWorker, loserWorker, winnerResult, loserResult);
  }

  @Test
  @DisplayName("连续多次发起新批次时每次仍只有一个认领胜出者")
  void assignWorker_onlyOneWorkerWins_acrossRepeatedLaunches() throws Exception {
    for (int i = 0; i < 5; i++) {
      assertOneClaimWinnerForFreshLaunch();
    }
  }

  @Test
  @DisplayName("任务登记在执行器池上时,认领后用具体实例替换并同步分区归属")
  void assignWorker_poolRouteIsReplacedByConcreteInstanceOnClaim() {
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, TENANT, "IMPORT", "DEFAULT", TriggerType.MANUAL);
    String workerInstanceId = "import-memory-pod-a";
    String workerPoolCode = "import-memory";
    workerRegistryMapper.saveLikeSdj(
        onlineWorker(TENANT, workerInstanceId, "DEFAULT", workerPoolCode));

    LaunchResponse response = workflowRunLaunchService.launch(LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.MANUAL)
        .requestId(seed.requestId())
        .traceId("trace-pool-claim")
        .params(Map.of())
        .build());
    assertThat(response.instanceNo()).isNotBlank();

    JobInstanceEntity jobInstance = jobInstanceForWorkflowRun(response);
    JobTaskQuery taskQuery = new JobTaskQuery(TENANT, jobInstance.getId(), null, null, null);
    JobTaskEntity task = jobTaskMapper.selectByQuery(taskQuery).getFirst();
    JobPartitionQuery partitionQuery =
        new JobPartitionQuery(TENANT, jobInstance.getId(), null, null);
    JobPartitionEntity partition = jobPartitionMapper.selectByQuery(partitionQuery).getFirst();
    String updateAssignedWorkerCodeSql = """
        update batch.job_task set assigned_worker_code = ? where tenant_id = ? and id = ?
        """;
    jdbcTemplate.update(
        updateAssignedWorkerCodeSql,
        workerPoolCode,
        TENANT,
        task.getId());

    JobTaskEntity claimed =
        taskExecutionService.assignWorker(TENANT, task.getId(), workerInstanceId);

    assertThat(claimed.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
    assertThat(claimed.getAssignedWorkerCode()).isEqualTo(workerInstanceId);
    assertThat(jobPartitionMapper.selectById(TENANT, partition.getId()).getWorkerCode())
        .isEqualTo(workerInstanceId);
  }

  @Test
  @DisplayName("同一执行器池内两个实例并发认领时仍只有一个胜出者")
  void assignWorker_onlyOneConcreteInstanceWinsWithinSamePool() throws Exception {
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, TENANT, "IMPORT", "DEFAULT", TriggerType.MANUAL);
    String suffix = String.valueOf(System.nanoTime());
    String workerPoolCode = "import-memory-" + suffix;
    String firstInstance = workerPoolCode + "-pod-a";
    String secondInstance = workerPoolCode + "-pod-b";
    workerRegistryMapper.saveLikeSdj(
        onlineWorker(TENANT, firstInstance, "DEFAULT", workerPoolCode));
    workerRegistryMapper.saveLikeSdj(
        onlineWorker(TENANT, secondInstance, "DEFAULT", workerPoolCode));

    LaunchResponse response = workflowRunLaunchService.launch(LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.MANUAL)
        .requestId(seed.requestId())
        .traceId("trace-concurrent-pool-claim")
        .params(Map.of())
        .build());
    assertThat(response.instanceNo()).isNotBlank();

    JobInstanceEntity jobInstance = jobInstanceForWorkflowRun(response);
    JobTaskQuery taskQuery = new JobTaskQuery(TENANT, jobInstance.getId(), null, null, null);
    JobTaskEntity task = jobTaskMapper.selectByQuery(taskQuery).getFirst();
    JobPartitionQuery partitionQuery =
        new JobPartitionQuery(TENANT, jobInstance.getId(), null, null);
    JobPartitionEntity partition = jobPartitionMapper.selectByQuery(partitionQuery).getFirst();
    String updateAssignedWorkerCodeSql = """
        update batch.job_task set assigned_worker_code = ? where tenant_id = ? and id = ?
        """;
    jdbcTemplate.update(
        updateAssignedWorkerCodeSql,
        workerPoolCode,
        TENANT,
        task.getId());

    CountDownLatch startGate = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<JobTaskEntity> firstFuture = pool.submit(() -> {
        startGate.await();
        return taskExecutionService.assignWorker(TENANT, task.getId(), firstInstance);
      });
      Future<JobTaskEntity> secondFuture = pool.submit(() -> {
        startGate.await();
        return taskExecutionService.assignWorker(TENANT, task.getId(), secondInstance);
      });

      startGate.countDown();

      assertConcurrentClaimRpcAndDbAgree(
          TENANT,
          task.getId(),
          partition.getId(),
          firstInstance,
          secondInstance,
          firstFuture.get(),
          secondFuture.get());
    } finally {
      pool.shutdownNow();
    }
  }

  private void assertOneClaimWinnerForFreshLaunch() throws Exception {
    WorkerRegistryCacheTestSupport.evictTenantWorkerSelectors(workerRegistryCache, TENANT);
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, TENANT, "IMPORT", "DEFAULT", TriggerType.MANUAL);
    String winnerWorker = seed.workerCode();
    String loserWorker = "worker-race-b-" + System.nanoTime();

    workerRegistryMapper.saveLikeSdj(onlineWorker(TENANT, loserWorker, "DEFAULT"));

    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.MANUAL)
        .requestId(seed.requestId())
        .traceId("trace-concurrent-claim-loop-" + seed.requestId())
        .params(Map.of())
        .build();
    LaunchResponse response = workflowRunLaunchService.launch(launchRequest);
    assertThat(response.instanceNo()).isNotBlank();

    JobInstanceEntity jobInstance = jobInstanceForWorkflowRun(response);
    List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(
        new JobTaskQuery(TENANT, jobInstance.getId(), null, null, null));
    assertThat(tasks).hasSize(1);
    JobTaskEntity task = tasks.get(0);
    List<JobPartitionEntity> partitions = jobPartitionMapper.selectByQuery(
        new JobPartitionQuery(TENANT, jobInstance.getId(), null, null));
    assertThat(partitions).hasSize(1);
    Long partitionId = partitions.get(0).getId();

    LaunchIntegrationFixture.refreshAssignableWorkersForTenant(jdbcTemplate, TENANT);

    CountDownLatch startGate = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<JobTaskEntity> winnerFuture = pool.submit(() -> {
        startGate.await();
        return taskExecutionService.assignWorker(TENANT, task.getId(), winnerWorker);
      });
      Future<JobTaskEntity> loserFuture = pool.submit(() -> {
        startGate.await();
        return taskExecutionService.assignWorker(TENANT, task.getId(), loserWorker);
      });

      startGate.countDown();

      JobTaskEntity winnerResult = winnerFuture.get();
      JobTaskEntity loserResult = loserFuture.get();

      assertConcurrentClaimRpcAndDbAgree(
          TENANT, task.getId(), partitionId, winnerWorker, loserWorker, winnerResult, loserResult);
    } finally {
      pool.shutdownNow();
    }
  }

  /** 并发认领的权威判据在 DB；RPC 返回值在事务回滚/陈旧快照场景下可能仍为 READY,故要求至少一方带出与 DB 一致的 assigned_worker_code。 */
  private void assertConcurrentClaimRpcAndDbAgree(
      String tenantId,
      Long taskId,
      Long partitionId,
      String winnerWorker,
      String loserWorker,
      JobTaskEntity winnerResult,
      JobTaskEntity loserResult) {
    assertThat(winnerResult).isNotNull();
    assertThat(loserResult).isNotNull();
    JobTaskEntity authoritative = jobTaskMapper.selectById(tenantId, taskId);
    assertThat(authoritative.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
    String assignee = authoritative.getAssignedWorkerCode();
    assertThat(assignee).isIn(winnerWorker, loserWorker);
    assertThat(Objects.equals(assignee, winnerResult.getAssignedWorkerCode())
            || Objects.equals(assignee, loserResult.getAssignedWorkerCode()))
        .as("至少一方 RPC 应反映 DB 已落地的认领结果")
        .isTrue();
    JobPartitionEntity finalPartition = jobPartitionMapper.selectById(tenantId, partitionId);
    assertThat(finalPartition.getPartitionStatus()).isEqualTo("RUNNING");
    assertThat(finalPartition.getWorkerCode()).isEqualTo(assignee);
  }

  private JobInstanceEntity jobInstanceForWorkflowRun(LaunchResponse response) {
    Long jobInstanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, TENANT, response.instanceNo());
    return jobInstanceMapper.selectById(TENANT, jobInstanceId);
  }

  private static WorkerRegistryEntity onlineWorker(
      String tenantId, String workerCode, String workerGroup) {
    return new WorkerRegistryEntity(
        null,
        tenantId,
        workerCode,
        workerGroup,
        new JsonbString("{}"),
        null,
        "ONLINE",
        BatchDateTimeSupport.utcNow(),
        0,
        10,
        null,
        null);
  }

  private static WorkerRegistryEntity onlineWorker(
      String tenantId, String workerCode, String workerGroup, String workerPoolCode) {
    return new WorkerRegistryEntity(
        null,
        tenantId,
        workerCode,
        workerGroup,
        new JsonbString("{}"),
        null,
        "ONLINE",
        BatchDateTimeSupport.utcNow(),
        0,
        10,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        workerPoolCode);
  }
}
