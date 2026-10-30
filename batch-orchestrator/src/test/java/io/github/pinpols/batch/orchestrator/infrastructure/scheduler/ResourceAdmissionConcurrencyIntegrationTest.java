package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.scheduler.PartitionThrottle;
import io.github.pinpols.batch.orchestrator.application.scheduler.ResourceScheduler;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskExecutionService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunManagementApplicationService;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.param.MarkInstanceRunningParam;
import io.github.pinpols.batch.orchestrator.domain.query.JobPartitionQuery;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceCheck;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingDecision;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.orchestrator.integration.support.WorkerRegistryCacheTestSupport;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 本场景由两个受控线程争抢槽位,关闭异步 kick 并延长周期,避免第三个后台派发者提前消费夹具。
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "batch.resource-scheduler.waiting-dispatch-interval-millis=3600000",
      "batch.resource-scheduler.waiting-dispatch-kick-enabled=false"
    })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("资源配额真实事务竞争: 新启动与等待释放共享最后一个槽位且只接纳一方")
class ResourceAdmissionConcurrencyIntegrationTest extends AbstractIntegrationTest {

  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 1, 15);
  private static final String WORKER_GROUP = "R21_ADMISSION";

  @MockitoSpyBean
  private PartitionThrottle partitionThrottle;

  @MockitoSpyBean
  private ResourceScheduler resourceScheduler;

  @MockitoSpyBean
  private JobInstanceMapper instanceMapper;

  private final WorkflowRunLaunchService launchService;
  private final OrchestratorJobMappers jobMappers;
  private final OrchestratorConfigCacheService configCacheService;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final BatchOrchestratorGovernanceProperties governance;
  private final WorkflowRunManagementApplicationService workflowManagement;
  private final WaitingPartitionDispatcher dispatcher;
  private final WaitingPartitionDispatchScheduler waitingScheduler;
  private final TaskExecutionService taskExecutionService;
  private final JobPartitionMapper partitionMapper;
  private final JobTaskMapper taskMapper;
  private final WorkerRegistryCache workerRegistryCache;
  private final JdbcTemplate jdbc;
  private final PlatformTransactionManager transactionManager;

  ResourceAdmissionConcurrencyIntegrationTest(
      WorkflowRunLaunchService launchService,
      OrchestratorJobMappers jobMappers,
      OrchestratorConfigCacheService configCacheService,
      OrchestratorGracefulShutdown gracefulShutdown,
      BatchOrchestratorGovernanceProperties governance,
      WorkflowRunManagementApplicationService workflowManagement,
      WaitingPartitionDispatcher dispatcher,
      WaitingPartitionDispatchScheduler waitingScheduler,
      TaskExecutionService taskExecutionService,
      JobPartitionMapper partitionMapper,
      JobTaskMapper taskMapper,
      WorkerRegistryCache workerRegistryCache,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager) {
    this.launchService = launchService;
    this.jobMappers = jobMappers;
    this.configCacheService = configCacheService;
    this.gracefulShutdown = gracefulShutdown;
    this.governance = governance;
    this.workflowManagement = workflowManagement;
    this.dispatcher = dispatcher;
    this.waitingScheduler = waitingScheduler;
    this.taskExecutionService = taskExecutionService;
    this.partitionMapper = partitionMapper;
    this.taskMapper = taskMapper;
    this.workerRegistryCache = workerRegistryCache;
    this.jdbc = jdbc;
    this.transactionManager = transactionManager;
  }

  @Test
  @DisplayName("公平组只有一个名额时先派发高权重租户,其完成后再释放低权重租户")
  void shouldDispatchHigherWeightThenReleasePeer_whenSharingOneJobSlot() {
    final String updateTenantQuotaPolicySql = """
        update batch.tenant_quota_policy set fair_share_weight = 10 where tenant_id = ?
    """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    String lowTenant = "r21-low-" + suffix;
    String highTenant = "r21-high-" + suffix;
    String group = "r21-weight-" + suffix;
    seedCaps(lowTenant, Cap.FAIR_SHARE_GROUP, group);
    seedCaps(highTenant, Cap.FAIR_SHARE_GROUP, group);
    jdbc.update(updateTenantQuotaPolicySql, highTenant);
    WaitingJob low = launchWaiting(lowTenant);
    WaitingJob high = launchWaiting(highTenant);
    enableWorker(lowTenant, low.seed());
    enableWorker(highTenant, high.seed());

    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
      waitingScheduler.dispatchWaitingPartitions();
      assertThat(instanceMapper.selectById(highTenant, high.instance().getId()).getInstanceStatus())
          .isEqualTo("RUNNING");
      assertThat(instanceMapper.selectById(lowTenant, low.instance().getId()).getInstanceStatus())
          .isEqualTo("WAITING");
    });
    assertThat(taskExecutionService.assignWorker(
            highTenant, high.task().getId(), high.seed().workerCode()))
        .isNotNull();
    JobPartitionEntity runningPartition =
        partitionMapper.selectById(highTenant, high.partition().getId());
    TaskOutcomeCommand highTenantOutcome = TaskOutcomeCommand.builder()
        .tenantId(highTenant)
        .taskId(high.task().getId())
        .partitionInvocationId(runningPartition.getCurrentInvocationId())
        .success(true)
        .build();
    taskExecutionService.applyTaskOutcome(highTenantOutcome);
    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
      waitingScheduler.dispatchWaitingPartitions();
      assertThat(instanceMapper.selectById(highTenant, high.instance().getId()).getInstanceStatus())
          .isEqualTo("SUCCESS");
      assertThat(instanceMapper.selectById(lowTenant, low.instance().getId()).getInstanceStatus())
          .isEqualTo("RUNNING");
    });
  }

  @Test
  @DisplayName("父实例运行状态更新失去版本竞争时,分区和任务释放及派发事件全部回滚")
  void shouldRollbackReleaseAndOutbox_whenParentActivationLosesCas() {
    final String selectCountSql = """
        select count(*) from batch.outbox_event
        where tenant_id = ? and aggregate_type = 'JOB_TASK' and aggregate_id = ?
        """;

    String tenant = "r21-cas-" + System.nanoTime();
    seedCaps(tenant, Cap.TENANT_JOB, "unused-group");
    WaitingJob job = launchWaiting(tenant);
    enableWorker(tenant, job.seed());
    doAnswer(invocation -> {
          MarkInstanceRunningParam param = invocation.getArgument(0);
          if (tenant.equals(param.getTenantId()) && job.instance().getId().equals(param.getId())) {
            return 0;
          }
          return invocation.callRealMethod();
        })
        .when(instanceMapper)
        .markRunning(any());

    assertThatThrownBy(() -> RlsTenantContextHolder.runWithTenant(
            tenant,
            () -> dispatcher.executeDispatch(
                job.partition(), job.task(), job.instance(), candidate())))
        .isInstanceOf(BizException.class);

    assertThat(instanceMapper.selectById(tenant, job.instance().getId()).getInstanceStatus())
        .isEqualTo("WAITING");
    assertThat(partitionMapper.selectById(tenant, job.partition().getId()).getPartitionStatus())
        .isEqualTo("WAITING");
    assertThat(taskMapper.selectById(tenant, job.task().getId()).getTaskStatus())
        .isEqualTo("CREATED");
    Long selectedOutboxEventCount =
        jdbc.queryForObject(selectCountSql, Long.class, tenant, job.task().getId());
    assertThat(selectedOutboxEventCount).isZero();
  }

  @Test
  @DisplayName("暂停 Workflow 后不释放其等待中的子作业分片")
  void shouldNotReleaseWaitingChildPartition_whenWorkflowPaused() {
    String selectRunSql = """
        select node.workflow_run_id from batch.workflow_node_run node
        join batch.workflow_run run on run.id = node.workflow_run_id
        where node.job_instance_id = ? and run.tenant_id = ?
        """;
    String markRunRunningSql = """
        update batch.workflow_run set run_status = 'RUNNING' where tenant_id = ? and id = ?
        """;
    String countDispatchSql = """
        select count(*) from batch.outbox_event
        where tenant_id = ? and aggregate_type = 'JOB_TASK' and aggregate_id = ?
        """;
    String tenant = "paused-wait-" + System.nanoTime();
    seedCaps(tenant, Cap.TENANT_JOB, "unused-group");
    WaitingJob job = launchWaiting(tenant);
    enableWorker(tenant, job.seed());
    Long runId = jdbc.queryForObject(selectRunSql, Long.class, job.instance().getId(), tenant);
    jdbc.update(markRunRunningSql, tenant, runId);
    workflowManagement.pause(tenant, runId);
    Long before =
        jdbc.queryForObject(countDispatchSql, Long.class, tenant, job.task().getId());
    assertThat(partitionMapper.selectWaitingPartitionsGlobal(100, "WAITING"))
        .extracting(JobPartitionEntity::getId)
        .doesNotContain(job.partition().getId());

    RlsTenantContextHolder.runWithTenant(
        tenant,
        () -> dispatcher.executeDispatch(job.partition(), job.task(), job.instance(), candidate()));

    assertThat(partitionMapper.selectById(tenant, job.partition().getId()).getPartitionStatus())
        .isEqualTo("WAITING");
    assertThat(taskMapper.selectById(tenant, job.task().getId()).getTaskStatus())
        .isEqualTo("CREATED");
    assertThat(
            jdbc.queryForObject(countDispatchSql, Long.class, tenant, job.task().getId()))
        .isEqualTo(before);
  }

  @Test
  @DisplayName("Workflow 恢复后等待中的子作业重新进入候选并完成派发")
  void shouldReleaseWaitingChildPartition_afterWorkflowResume() {
    String selectRunSql = """
        select node.workflow_run_id from batch.workflow_node_run node
        join batch.workflow_run run on run.id = node.workflow_run_id
        where node.job_instance_id = ? and run.tenant_id = ?
        """;
    String markRunRunningSql = """
        update batch.workflow_run set run_status = 'RUNNING' where tenant_id = ? and id = ?
        """;
    String countDispatchSql = """
        select count(*) from batch.outbox_event
        where tenant_id = ? and aggregate_type = 'JOB_TASK' and aggregate_id = ?
        """;
    String tenant = "resumed-wait-" + System.nanoTime();
    seedCaps(tenant, Cap.TENANT_JOB, "unused-group");
    WaitingJob job = launchWaiting(tenant);
    enableWorker(tenant, job.seed());
    Long runId = jdbc.queryForObject(selectRunSql, Long.class, job.instance().getId(), tenant);
    jdbc.update(markRunRunningSql, tenant, runId);
    workflowManagement.pause(tenant, runId);
    Long before =
        jdbc.queryForObject(countDispatchSql, Long.class, tenant, job.task().getId());
    workflowManagement.resume(tenant, runId);

    assertThat(partitionMapper.selectWaitingPartitionsGlobal(100, "WAITING"))
        .extracting(JobPartitionEntity::getId)
        .contains(job.partition().getId());
    RlsTenantContextHolder.runWithTenant(
        tenant,
        () -> dispatcher.executeDispatch(job.partition(), job.task(), job.instance(), candidate()));
    assertThat(partitionMapper.selectById(tenant, job.partition().getId()).getPartitionStatus())
        .isEqualTo("READY");
    assertThat(taskMapper.selectById(tenant, job.task().getId()).getTaskStatus())
        .isEqualTo("READY");
    assertThat(
            jdbc.queryForObject(countDispatchSql, Long.class, tenant, job.task().getId()))
        .isEqualTo(before + 1);
  }

  @Test
  @DisplayName("暂停事务持有 Workflow 锁时释放等待，提交暂停后不派发")
  void shouldWaitForPauseCommit_beforeReleasingChildPartition() throws Exception {
    String selectRunSql = """
        select node.workflow_run_id from batch.workflow_node_run node
        join batch.workflow_run run on run.id = node.workflow_run_id
        where node.job_instance_id = ? and run.tenant_id = ?
        """;
    String markRunRunningSql = """
        update batch.workflow_run set run_status = 'RUNNING' where tenant_id = ? and id = ?
        """;
    String selectPidSql = "select pg_backend_pid()";
    String selectBlockedSql =
        "select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))";
    String countDispatchSql = """
        select count(*) from batch.outbox_event
        where tenant_id = ? and aggregate_type = 'JOB_TASK' and aggregate_id = ?
        """;
    String tenant = "pause-race-" + System.nanoTime();
    seedCaps(tenant, Cap.TENANT_JOB, "unused-group");
    WaitingJob job = launchWaiting(tenant);
    enableWorker(tenant, job.seed());
    Long runId = jdbc.queryForObject(selectRunSql, Long.class, job.instance().getId(), tenant);
    jdbc.update(markRunRunningSql, tenant, runId);
    Long before =
        jdbc.queryForObject(countDispatchSql, Long.class, tenant, job.task().getId());
    CountDownLatch pausedBeforeCommit = new CountDownLatch(1);
    CountDownLatch allowCommit = new CountDownLatch(1);
    AtomicInteger pausePid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> pause = executor.submit(() -> new TransactionTemplate(transactionManager)
          .executeWithoutResult(status -> {
            pausePid.set(jdbc.queryForObject(selectPidSql, Integer.class));
            workflowManagement.pause(tenant, runId);
            pausedBeforeCommit.countDown();
            try {
              if (!allowCommit.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("pause commit was not released");
              }
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(ex);
            }
          }));
      assertThat(pausedBeforeCommit.await(15, TimeUnit.SECONDS)).isTrue();
      Future<?> release = executor.submit(() -> RlsTenantContextHolder.runWithTenant(
          tenant,
          () -> dispatcher.executeDispatch(
              job.partition(), job.task(), job.instance(), candidate())));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> assertThat(jdbc.queryForObject(selectBlockedSql, Long.class, pausePid.get()))
                  .isGreaterThan(0));
      allowCommit.countDown();
      pause.get(15, TimeUnit.SECONDS);
      release.get(15, TimeUnit.SECONDS);
    } finally {
      allowCommit.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(partitionMapper.selectById(tenant, job.partition().getId()).getPartitionStatus())
        .isEqualTo("WAITING");
    assertThat(taskMapper.selectById(tenant, job.task().getId()).getTaskStatus())
        .isEqualTo("CREATED");
    assertThat(
            jdbc.queryForObject(countDispatchSql, Long.class, tenant, job.task().getId()))
        .isEqualTo(before);
  }

  @Test
  @DisplayName("前一个租户无法派发时，后续租户仍能在后续轮次获得评估")
  void shouldReachLaterTenant_whenEarlierWaitingPartitionCannotDispatch() {
    String suffix = Long.toUnsignedString(System.nanoTime());
    String blockedTenant = "blocked-" + suffix;
    String readyTenant = "ready-" + suffix;
    seedCaps(blockedTenant, Cap.TENANT_JOB, "unused-group");
    seedCaps(readyTenant, Cap.TENANT_JOB, "unused-group");
    WaitingJob blocked = launchWaiting(blockedTenant);
    WaitingJob ready = launchWaiting(readyTenant);
    var initialCursor = partitionMapper.selectWaitingPartitionScanCursor();
    assertThat(partitionMapper.advanceWaitingPartitionScanCursor(
            blocked.partition().getId() - 1,
            ready.partition().getId(),
            false,
            initialCursor.version()))
        .isEqualTo(1);
    enableWorker(readyTenant, ready.seed());
    int originalBatchSize = governance.resourceScheduler().getWaitingDispatchBatchSize();
    governance.resourceScheduler().setWaitingDispatchBatchSize(1);
    try {
      await()
          .atMost(Duration.ofSeconds(18))
          .pollInterval(Duration.ofSeconds(1))
          .untilAsserted(() -> {
            waitingScheduler.dispatchWaitingPartitions();
            assertThat(instanceMapper
                    .selectById(readyTenant, ready.instance().getId())
                    .getInstanceStatus())
                .isEqualTo("RUNNING");
          });
      assertThat(instanceMapper
              .selectById(blockedTenant, blocked.instance().getId())
              .getInstanceStatus())
          .isEqualTo("WAITING");
    } finally {
      governance.resourceScheduler().setWaitingDispatchBatchSize(originalBatchSize);
    }
  }

  @Test
  @DisplayName("调度器实例切换后继续扫描，不能反复卡在不可派发的队首")
  void shouldContinueWaitingScanAfterSchedulerInstanceChanges() {
    String suffix = Long.toUnsignedString(System.nanoTime());
    String blockedTenant = "blocked-switch-" + suffix;
    String readyTenant = "ready-switch-" + suffix;
    seedCaps(blockedTenant, Cap.TENANT_JOB, "unused-group");
    seedCaps(readyTenant, Cap.TENANT_JOB, "unused-group");
    WaitingJob blocked = launchWaiting(blockedTenant);
    WaitingJob ready = launchWaiting(readyTenant);
    var initialCursor = partitionMapper.selectWaitingPartitionScanCursor();
    assertThat(partitionMapper.advanceWaitingPartitionScanCursor(
            blocked.partition().getId() - 1,
            ready.partition().getId(),
            false,
            initialCursor.version()))
        .isEqualTo(1);
    enableWorker(readyTenant, ready.seed());
    int originalBatchSize = governance.resourceScheduler().getWaitingDispatchBatchSize();
    governance.resourceScheduler().setWaitingDispatchBatchSize(1);
    try {
      WaitingPartitionDispatchScheduler first = new WaitingPartitionDispatchScheduler(
          resourceScheduler,
          governance,
          jobMappers,
          configCacheService,
          gracefulShutdown,
          dispatcher);
      WaitingPartitionDispatchScheduler second = new WaitingPartitionDispatchScheduler(
          resourceScheduler,
          governance,
          jobMappers,
          configCacheService,
          gracefulShutdown,
          dispatcher);
      first.dispatchWaitingPartitions();
      second.dispatchWaitingPartitions();
      assertThat(instanceMapper
              .selectById(blockedTenant, blocked.instance().getId())
              .getInstanceStatus())
          .isEqualTo("WAITING");
      assertThat(
              instanceMapper.selectById(readyTenant, ready.instance().getId()).getInstanceStatus())
          .isEqualTo("RUNNING");
    } finally {
      governance.resourceScheduler().setWaitingDispatchBatchSize(originalBatchSize);
    }
  }

  @Test
  @DisplayName("两个调度器同时推进同一版本时只有一个提交成功")
  void shouldFenceConcurrentWaitingScanCursorUpdates() throws Exception {
    var initial = partitionMapper.selectWaitingPartitionScanCursor();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> first = executor.submit(() -> {
        start.await();
        return partitionMapper.advanceWaitingPartitionScanCursor(
            101, 102, false, initial.version());
      });
      Future<Integer> second = executor.submit(() -> {
        start.await();
        return partitionMapper.advanceWaitingPartitionScanCursor(
            102, 102, false, initial.version());
      });
      start.countDown();
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(0, 1);
      var updated = partitionMapper.selectWaitingPartitionScanCursor();
      assertThat(updated.version()).isEqualTo(initial.version() + 1);
      assertThat(updated.lastPartitionId()).isIn(101L, 102L);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("新分片持续加入时，周期上界使旧等待分片在恢复后仍可派发")
  void shouldRevisitEarlierWaitingJob_whenNewPartitionsKeepArriving() {
    String suffix = Long.toUnsignedString(System.nanoTime());
    String firstTenant = "cycle-first-" + suffix;
    String secondTenant = "cycle-second-" + suffix;
    String thirdTenant = "cycle-third-" + suffix;
    seedCaps(firstTenant, Cap.TENANT_JOB, "unused-group");
    seedCaps(secondTenant, Cap.TENANT_JOB, "unused-group");
    seedCaps(thirdTenant, Cap.TENANT_JOB, "unused-group");
    WaitingJob first = launchWaiting(firstTenant);
    WaitingJob second = launchWaiting(secondTenant);
    var cursor = partitionMapper.selectWaitingPartitionScanCursor();
    assertThat(partitionMapper.advanceWaitingPartitionScanCursor(
            first.partition().getId() - 1, second.partition().getId(), false, cursor.version()))
        .isEqualTo(1);
    String countEarlierSql = """
        select count(*) from batch.job_partition where partition_status = 'WAITING' and id < ?
        """;
    long earlierRows =
        jdbc.queryForObject(countEarlierSql, Long.class, first.partition().getId());
    int originalBatchSize = governance.resourceScheduler().getWaitingDispatchBatchSize();
    governance.resourceScheduler().setWaitingDispatchBatchSize(1);
    try {
      WaitingPartitionDispatchScheduler scanner = new WaitingPartitionDispatchScheduler(
          resourceScheduler,
          governance,
          jobMappers,
          configCacheService,
          gracefulShutdown,
          dispatcher);
      scanner.dispatchWaitingPartitions();
      launchWaiting(thirdTenant);
      scanner.dispatchWaitingPartitions();
      enableWorker(firstTenant, first.seed());
      for (int i = 0; i < earlierRows + 3; i++) {
        String newTenant = "cycle-tail-" + i + "-" + suffix;
        seedCaps(newTenant, Cap.TENANT_JOB, "unused-group");
        launchWaiting(newTenant);
        scanner.dispatchWaitingPartitions();
        if ("RUNNING"
            .equals(instanceMapper
                .selectById(firstTenant, first.instance().getId())
                .getInstanceStatus())) {
          break;
        }
      }
      assertThat(
              instanceMapper.selectById(firstTenant, first.instance().getId()).getInstanceStatus())
          .isEqualTo("RUNNING");
      assertThat(partitionMapper
              .selectById(firstTenant, first.partition().getId())
              .getPartitionStatus())
          .isEqualTo("READY");
    } finally {
      governance.resourceScheduler().setWaitingDispatchBatchSize(originalBatchSize);
    }
  }

  @Test
  @DisplayName("扫描水位提交后处理中断，后续调度继续并在回绕后派发遗漏任务")
  void shouldRevisitCandidateAfterFailureFollowingCursorCommit() {
    String countDispatchSql = """
        select count(*) from batch.outbox_event
        where tenant_id = ? and aggregate_type = 'JOB_TASK' and aggregate_id = ?
        """;
    String countEarlierSql = """
        select count(*) from batch.job_partition where partition_status = 'WAITING' and id < ?
        """;
    String suffix = Long.toUnsignedString(System.nanoTime());
    String firstTenant = "recover-first-" + suffix;
    String secondTenant = "recover-second-" + suffix;
    seedCaps(firstTenant, Cap.TENANT_JOB, "unused-group");
    seedCaps(secondTenant, Cap.TENANT_JOB, "unused-group");
    WaitingJob first = launchWaiting(firstTenant);
    WaitingJob second = launchWaiting(secondTenant);
    enableWorker(firstTenant, first.seed());
    enableWorker(secondTenant, second.seed());
    var initial = partitionMapper.selectWaitingPartitionScanCursor();
    assertThat(partitionMapper.advanceWaitingPartitionScanCursor(
            first.partition().getId() - 1, second.partition().getId(), false, initial.version()))
        .isEqualTo(1);
    long earlierRows =
        jdbc.queryForObject(countEarlierSql, Long.class, first.partition().getId());
    int originalBatchSize = governance.resourceScheduler().getWaitingDispatchBatchSize();
    governance.resourceScheduler().setWaitingDispatchBatchSize(1);
    AtomicBoolean injectFailure = new AtomicBoolean(true);
    doAnswer(invocation -> {
          ResourceSchedulingRequest request = invocation.getArgument(0);
          if (firstTenant.equals(request.getTenantId()) && injectFailure.getAndSet(false)) {
            throw new AssertionError("injected interruption after cursor commit");
          }
          return invocation.callRealMethod();
        })
        .when(resourceScheduler)
        .preview(any(ResourceSchedulingRequest.class));
    try {
      WaitingPartitionDispatchScheduler firstScanner = new WaitingPartitionDispatchScheduler(
          resourceScheduler,
          governance,
          jobMappers,
          configCacheService,
          gracefulShutdown,
          dispatcher);
      WaitingPartitionDispatchScheduler successor = new WaitingPartitionDispatchScheduler(
          resourceScheduler,
          governance,
          jobMappers,
          configCacheService,
          gracefulShutdown,
          dispatcher);
      assertThatThrownBy(firstScanner::dispatchWaitingPartitions)
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("injected interruption");
      assertThat(partitionMapper.selectWaitingPartitionScanCursor().lastPartitionId())
          .isEqualTo(first.partition().getId());
      assertThat(partitionMapper
              .selectById(firstTenant, first.partition().getId())
              .getPartitionStatus())
          .isEqualTo("WAITING");
      assertThat(jdbc.queryForObject(
              countDispatchSql, Long.class, firstTenant, first.task().getId()))
          .isZero();

      successor.dispatchWaitingPartitions();
      assertThat(instanceMapper
              .selectById(secondTenant, second.instance().getId())
              .getInstanceStatus())
          .isEqualTo("RUNNING");
      for (int i = 0; i < earlierRows + 3; i++) {
        successor.dispatchWaitingPartitions();
        if ("RUNNING"
            .equals(instanceMapper
                .selectById(firstTenant, first.instance().getId())
                .getInstanceStatus())) {
          break;
        }
      }
      assertThat(
              instanceMapper.selectById(firstTenant, first.instance().getId()).getInstanceStatus())
          .isEqualTo("RUNNING");
      assertThat(jdbc.queryForObject(
              countDispatchSql, Long.class, firstTenant, first.task().getId()))
          .isEqualTo(1L);
      assertThat(jdbc.queryForObject(
              countDispatchSql, Long.class, secondTenant, second.task().getId()))
          .isEqualTo(1L);
    } finally {
      governance.resourceScheduler().setWaitingDispatchBatchSize(originalBatchSize);
    }
  }

  @Test
  @DisplayName("真实数据库中较早演练分片不会排在同周期较晚的正式分片之前")
  void shouldDispatchFormalBeforeEarlierDryRunAcrossBatches() {
    String countDispatchSql = """
        select count(*) from batch.outbox_event
        where tenant_id = ? and aggregate_type = 'JOB_TASK' and aggregate_id = ?
        """;
    String suffix = Long.toUnsignedString(System.nanoTime());
    String dryTenant = "priority-dry-" + suffix;
    String formalTenant = "priority-formal-" + suffix;
    seedCaps(dryTenant, Cap.TENANT_JOB, "unused-group");
    seedCaps(formalTenant, Cap.TENANT_JOB, "unused-group");
    WaitingJob dry = launchDryWaiting(dryTenant);
    WaitingJob formal = launchWaiting(formalTenant);
    enableDryWorker(dryTenant, dry.seed());
    enableWorker(formalTenant, formal.seed());
    var initial = partitionMapper.selectWaitingPartitionScanCursor();
    assertThat(partitionMapper.advanceWaitingPartitionScanCursor(
            dry.partition().getId() - 1, formal.partition().getId(), false, initial.version()))
        .isEqualTo(1);
    int originalBatchSize = governance.resourceScheduler().getWaitingDispatchBatchSize();
    governance.resourceScheduler().setWaitingDispatchBatchSize(1);
    try {
      WaitingPartitionDispatchScheduler scanner = new WaitingPartitionDispatchScheduler(
          resourceScheduler,
          governance,
          jobMappers,
          configCacheService,
          gracefulShutdown,
          dispatcher);
      scanner.dispatchWaitingPartitions();
      assertThat(instanceMapper
              .selectById(formalTenant, formal.instance().getId())
              .getInstanceStatus())
          .isEqualTo("RUNNING");
      assertThat(
              partitionMapper.selectById(dryTenant, dry.partition().getId()).getPartitionStatus())
          .isEqualTo("WAITING");
      assertThat(jdbc.queryForObject(
              countDispatchSql, Long.class, formalTenant, formal.task().getId()))
          .isEqualTo(1L);
      assertThat(jdbc.queryForObject(
              countDispatchSql, Long.class, dryTenant, dry.task().getId()))
          .isZero();
      WaitingPartitionDispatchScheduler successor = new WaitingPartitionDispatchScheduler(
          resourceScheduler,
          governance,
          jobMappers,
          configCacheService,
          gracefulShutdown,
          dispatcher);
      successor.dispatchWaitingPartitions();
      assertThat(
              partitionMapper.selectById(dryTenant, dry.partition().getId()).getPartitionStatus())
          .isEqualTo("READY");
      assertThat(jdbc.queryForObject(
              countDispatchSql, Long.class, dryTenant, dry.task().getId()))
          .isEqualTo(1L);
    } finally {
      governance.resourceScheduler().setWaitingDispatchBatchSize(originalBatchSize);
    }
  }

  @Test
  @DisplayName("正式分片没有可用 Worker 时，演练分片仍可使用自己的空闲 Worker")
  void shouldDispatchDryAfterFormalCandidateIsBlocked() {
    String suffix = Long.toUnsignedString(System.nanoTime());
    String dryTenant = "fallback-dry-" + suffix;
    String formalTenant = "fallback-formal-" + suffix;
    seedCaps(dryTenant, Cap.TENANT_JOB, "unused-group");
    seedCaps(formalTenant, Cap.TENANT_JOB, "unused-group");
    WaitingJob dry = launchDryWaiting(dryTenant);
    WaitingJob formal = launchWaiting(formalTenant);
    enableDryWorker(dryTenant, dry.seed());
    var initial = partitionMapper.selectWaitingPartitionScanCursor();
    assertThat(partitionMapper.advanceWaitingPartitionScanCursor(
            dry.partition().getId() - 1, formal.partition().getId(), false, initial.version()))
        .isEqualTo(1);
    int originalBatchSize = governance.resourceScheduler().getWaitingDispatchBatchSize();
    governance.resourceScheduler().setWaitingDispatchBatchSize(1);
    try {
      WaitingPartitionDispatchScheduler scanner = new WaitingPartitionDispatchScheduler(
          resourceScheduler,
          governance,
          jobMappers,
          configCacheService,
          gracefulShutdown,
          dispatcher);
      scanner.dispatchWaitingPartitions();
      assertThat(instanceMapper
              .selectById(formalTenant, formal.instance().getId())
              .getInstanceStatus())
          .isEqualTo("WAITING");
      scanner.dispatchWaitingPartitions();
      assertThat(
              partitionMapper.selectById(dryTenant, dry.partition().getId()).getPartitionStatus())
          .isEqualTo("READY");
    } finally {
      governance.resourceScheduler().setWaitingDispatchBatchSize(originalBatchSize);
    }
  }

  @Test
  @DisplayName("周期内没有正式候选时，演练分片同轮可被派发")
  void shouldDispatchDryWhenNoFormalCandidateExists() {
    String tenant = "only-dry-" + Long.toUnsignedString(System.nanoTime());
    seedCaps(tenant, Cap.TENANT_JOB, "unused-group");
    WaitingJob dry = launchDryWaiting(tenant);
    enableDryWorker(tenant, dry.seed());
    var initial = partitionMapper.selectWaitingPartitionScanCursor();
    assertThat(partitionMapper.advanceWaitingPartitionScanCursor(
            dry.partition().getId() - 1, dry.partition().getId(), false, initial.version()))
        .isEqualTo(1);
    int originalBatchSize = governance.resourceScheduler().getWaitingDispatchBatchSize();
    governance.resourceScheduler().setWaitingDispatchBatchSize(1);
    try {
      WaitingPartitionDispatchScheduler scanner = new WaitingPartitionDispatchScheduler(
          resourceScheduler,
          governance,
          jobMappers,
          configCacheService,
          gracefulShutdown,
          dispatcher);
      scanner.dispatchWaitingPartitions();
      assertThat(partitionMapper.selectById(tenant, dry.partition().getId()).getPartitionStatus())
          .isEqualTo("READY");
    } finally {
      governance.resourceScheduler().setWaitingDispatchBatchSize(originalBatchSize);
    }
  }

  private WaitingJob launchDryWaiting(String tenant) {
    String markDryRequestSql = """
        update batch.trigger_request set dry_run = true where tenant_id = ? and request_id = ?
        """;
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbc, tenant, "GENERAL", WORKER_GROUP, TriggerType.API, BIZ_DATE);
    jdbc.update(markDryRequestSql, tenant, seed.requestId());
    var response =
        launchService.launch(request(tenant, seed).toBuilder().dryRun(true).build());
    Long id =
        LaunchIntegrationFixture.selectDispatchedJobInstanceId(jdbc, tenant, response.instanceNo());
    JobInstanceEntity instance = instanceMapper.selectById(tenant, id);
    assertThat(instance.getInstanceStatus()).isEqualTo("WAITING");
    JobPartitionEntity partition = partitionMapper
        .selectByQuery(JobPartitionQuery.ofJobInstance(tenant, id))
        .getFirst();
    return new WaitingJob(
        seed,
        instance,
        partition,
        taskMapper.selectByPartitionAndSeq(tenant, partition.getId(), 1));
  }

  private void enableDryWorker(String tenant, WorkflowLaunchSeed seed) {
    String insertWorkerSql = """
        insert into batch.worker_registry (
            tenant_id, worker_code, worker_group, capability_tags, status, heartbeat_at, current_load
        ) values (?, ?, ?, '["dry-run-safe"]'::jsonb, 'ONLINE', now(), 0)
        """;
    jdbc.update(insertWorkerSql, tenant, seed.workerCode(), WORKER_GROUP);
    WorkerRegistryCacheTestSupport.evictTenantWorkerSelectors(workerRegistryCache, tenant);
  }

  private WaitingJob launchWaiting(String tenant) {
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbc, tenant, "GENERAL", WORKER_GROUP, TriggerType.API, BIZ_DATE);
    var response = launchService.launch(request(tenant, seed));
    Long id =
        LaunchIntegrationFixture.selectDispatchedJobInstanceId(jdbc, tenant, response.instanceNo());
    JobInstanceEntity instance = instanceMapper.selectById(tenant, id);
    assertThat(instance.getInstanceStatus()).isEqualTo("WAITING");
    JobPartitionQuery partitionQuery = JobPartitionQuery.ofJobInstance(tenant, id);
    List<JobPartitionEntity> partitions = partitionMapper.selectByQuery(partitionQuery);
    JobPartitionEntity partition = partitions.getFirst();
    return new WaitingJob(
        seed,
        instance,
        partition,
        taskMapper.selectByPartitionAndSeq(tenant, partition.getId(), 1));
  }

  private void enableWorker(String tenant, WorkflowLaunchSeed seed) {
    final String insertWorkerRegistrySql = """
        insert into batch.worker_registry (
            tenant_id, worker_code, worker_group, capability_tags, status, heartbeat_at, current_load
        ) values (?, ?, ?, '[]'::jsonb, 'ONLINE', now(), 0)
        """;

    jdbc.update(insertWorkerRegistrySql, tenant, seed.workerCode(), WORKER_GROUP);
    WorkerRegistryCacheTestSupport.evictTenantWorkerSelectors(workerRegistryCache, tenant);
  }

  private record WaitingJob(
      WorkflowLaunchSeed seed,
      JobInstanceEntity instance,
      JobPartitionEntity partition,
      JobTaskEntity task) {}

  @ParameterizedTest
  @EnumSource(Cap.class)
  @DisplayName("新作业持有最后一个名额至提交前,等待释放被数据库锁阻塞且提交后保持等待")
  void shouldKeepWaitingReleaseDeferred_whenNewLaunchOwnsLastSlot(Cap cap) throws Exception {
    final String insertWorkerRegistrySql = """
        insert into batch.worker_registry (
            tenant_id, worker_code, worker_group, capability_tags, status, heartbeat_at, current_load
        ) values (?, ?, ?, '[]'::jsonb, 'ONLINE', now(), 0)
        """;
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;
    final String selectStatementSql = """
        select ? = any(pg_blocking_pids(?))
    """;
    final String selectCountSql = """
          select count(*) from batch.job_instance
          where tenant_id in (?, ?) and instance_status in ('READY', 'RUNNING')
          """;
    final String selectCountSql2 = """
          select count(*) from batch.job_partition
          where tenant_id in (?, ?) and partition_status in ('READY', 'RUNNING', 'RETRYING')
          """;
    final String selectCountSql3 = """
          select count(*) from batch.outbox_event
          where tenant_id = ? and aggregate_type = 'JOB_TASK' and aggregate_id = ?
          """;

    String suffix = cap.name().toLowerCase() + "-" + System.nanoTime();
    String waitingTenant = "r21-wait-" + suffix;
    String launchTenant = cap == Cap.FAIR_SHARE_GROUP ? "r21-new-" + suffix : waitingTenant;
    String sharedGroup = "r21-group-" + suffix;
    seedCaps(waitingTenant, cap, sharedGroup);
    if (!launchTenant.equals(waitingTenant)) {
      seedCaps(launchTenant, cap, sharedGroup);
    }
    WorkflowLaunchSeed waitingSeed = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbc, waitingTenant, "GENERAL", WORKER_GROUP, TriggerType.API, BIZ_DATE);
    var waitingResponse = launchService.launch(request(waitingTenant, waitingSeed));
    Long waitingId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbc, waitingTenant, waitingResponse.instanceNo());
    JobInstanceEntity waitingInstance = instanceMapper.selectById(waitingTenant, waitingId);
    assertThat(waitingInstance.getInstanceStatus()).isEqualTo("WAITING");
    JobPartitionQuery partitionQuery = JobPartitionQuery.ofJobInstance(waitingTenant, waitingId);
    List<JobPartitionEntity> partitions = partitionMapper.selectByQuery(partitionQuery);
    JobPartitionEntity partition = partitions.getFirst();
    JobTaskEntity task = taskMapper.selectByPartitionAndSeq(waitingTenant, partition.getId(), 1);
    WorkflowLaunchSeed launchSeed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbc, launchTenant, "GENERAL", WORKER_GROUP, TriggerType.API, BIZ_DATE);
    jdbc.update(insertWorkerRegistrySql, waitingTenant, waitingSeed.workerCode(), WORKER_GROUP);
    WorkerRegistryCacheTestSupport.evictTenantWorkerSelectors(workerRegistryCache, waitingTenant);

    CountDownLatch launchChecked = new CountDownLatch(1);
    CountDownLatch releaseLaunch = new CountDownLatch(1);
    CountDownLatch waitingEntered = new CountDownLatch(1);
    AtomicInteger launchPid = new AtomicInteger();
    AtomicInteger waitingPid = new AtomicInteger();
    doAnswer(invocation -> {
          ResourceSchedulingRequest schedulingRequest = invocation.getArgument(0);
          ResourceCheck check = (ResourceCheck) invocation.callRealMethod();
          if (launchSeed.jobCode().equals(schedulingRequest.getJobCode()) && check.allowed()) {
            launchPid.set(jdbc.queryForObject(selectPgBackendPidSql, Integer.class));
            launchChecked.countDown();
            assertThat(releaseLaunch.await(20, TimeUnit.SECONDS)).isTrue();
          }
          return check;
        })
        .when(partitionThrottle)
        .check(any(), any());
    doAnswer(invocation -> {
          ResourceSchedulingRequest schedulingRequest = invocation.getArgument(0);
          if (waitingSeed.jobCode().equals(schedulingRequest.getJobCode())) {
            waitingPid.set(jdbc.queryForObject(selectPgBackendPidSql, Integer.class));
            waitingEntered.countDown();
          }
          return invocation.callRealMethod();
        })
        .when(resourceScheduler)
        .schedule(any());

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> launching =
          executor.submit(() -> launchService.launch(request(launchTenant, launchSeed)));
      assertThat(launchChecked.await(15, TimeUnit.SECONDS)).isTrue();
      Future<?> releasing = executor.submit(() -> RlsTenantContextHolder.runWithTenant(
          waitingTenant,
          () -> dispatcher.executeDispatch(partition, task, waitingInstance, candidate())));
      assertThat(waitingEntered.await(10, TimeUnit.SECONDS)).isTrue();
      await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
        Boolean selectedStatement = jdbc.queryForObject(
            selectStatementSql, Boolean.class, launchPid.get(), waitingPid.get());
        assertThat(selectedStatement)
            .as("the new-launch transaction holds the shared admission lock until commit")
            .isTrue();
      });

      releaseLaunch.countDown();
      launching.get(30, TimeUnit.SECONDS);
      releasing.get(30, TimeUnit.SECONDS);
      assertThat(instanceMapper.selectById(waitingTenant, waitingId).getInstanceStatus())
          .isEqualTo("WAITING");
      assertThat(partitionMapper.selectById(waitingTenant, partition.getId()).getPartitionStatus())
          .isEqualTo("WAITING");
      assertThat(taskMapper.selectById(waitingTenant, task.getId()).getTaskStatus())
          .isEqualTo("CREATED");
      Long selectedJobInstanceCount =
          jdbc.queryForObject(selectCountSql, Long.class, waitingTenant, launchTenant);
      assertThat(selectedJobInstanceCount).isOne();
      Long selectedJobPartitionCount =
          jdbc.queryForObject(selectCountSql2, Long.class, waitingTenant, launchTenant);
      assertThat(selectedJobPartitionCount).isOne();
      Long selectedOutboxEventCount =
          jdbc.queryForObject(selectCountSql3, Long.class, waitingTenant, task.getId());
      assertThat(selectedOutboxEventCount).isZero();
    } finally {
      releaseLaunch.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private void seedCaps(String tenant, Cap cap, String sharedGroup) {
    final String insertTenantQuotaPolicySql = """
        insert into batch.tenant_quota_policy (
            tenant_id, policy_code, max_running_jobs_per_tenant, max_partitions_per_tenant,
            max_qps_per_tenant, fair_share_weight, fair_share_group, enabled, burst_limit,
            partition_burst_limit, quota_reset_policy, group_shared_max_running_jobs, exceeded_strategy
        ) values (?, 'r21-cap', ?, ?, 0, 1, ?, true, 0, 0, 'NONE', ?, 'QUEUE_DEFER')
        """;
    final String insertResourceQueueSql = """
        insert into batch.resource_queue (
            tenant_id, queue_code, queue_name, queue_type, max_running_jobs,
            max_running_partitions, max_qps, worker_group, enabled, burst_limit, quota_reset_policy
        ) values (?, 'q-it', 'R21 admission', 'MIXED', ?, ?, 0, ?, true, 0, 'NONE')
        """;

    jdbc.update(
        insertTenantQuotaPolicySql,
        tenant,
        cap == Cap.TENANT_JOB ? 1 : 0,
        cap == Cap.TENANT_PARTITION ? 1 : 0,
        cap == Cap.FAIR_SHARE_GROUP ? sharedGroup : null,
        cap == Cap.FAIR_SHARE_GROUP ? 1 : 0);
    jdbc.update(
        insertResourceQueueSql,
        tenant,
        cap == Cap.QUEUE_JOB ? 1 : 0,
        cap == Cap.QUEUE_PARTITION ? 1 : 0,
        WORKER_GROUP);
  }

  private static LaunchRequest request(String tenant, WorkflowLaunchSeed seed) {
    return LaunchRequest.builder()
        .tenantId(tenant)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .requestId(seed.requestId())
        .traceId("trace-" + seed.requestId())
        .triggerType(TriggerType.API)
        .bizDate(BIZ_DATE)
        .build();
  }

  private static ResourceSchedulingDecision candidate() {
    ResourceSchedulingDecision decision = new ResourceSchedulingDecision();
    decision.setDispatchable(true);
    return decision;
  }

  enum Cap {
    TENANT_JOB,
    TENANT_PARTITION,
    QUEUE_JOB,
    QUEUE_PARTITION,
    FAIR_SHARE_GROUP
  }
}
