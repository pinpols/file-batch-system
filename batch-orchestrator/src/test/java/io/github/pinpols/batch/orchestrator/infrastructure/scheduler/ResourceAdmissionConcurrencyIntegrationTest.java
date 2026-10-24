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
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskExecutionService;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.param.MarkInstanceRunningParam;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobPartitionQuery;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceCheck;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingDecision;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

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

  @MockitoSpyBean private PartitionThrottle partitionThrottle;
  @MockitoSpyBean private ResourceScheduler resourceScheduler;
  @MockitoSpyBean private JobInstanceMapper instanceMapper;

  private final WorkflowRunLaunchService launchService;
  private final WaitingPartitionDispatcher dispatcher;
  private final WaitingPartitionDispatchScheduler waitingScheduler;
  private final TaskExecutionService taskExecutionService;
  private final JobPartitionMapper partitionMapper;
  private final JobTaskMapper taskMapper;
  private final WorkerRegistryCache workerRegistryCache;
  private final JdbcTemplate jdbc;

  ResourceAdmissionConcurrencyIntegrationTest(
      WorkflowRunLaunchService launchService,
      WaitingPartitionDispatcher dispatcher,
      WaitingPartitionDispatchScheduler waitingScheduler,
      TaskExecutionService taskExecutionService,
      JobPartitionMapper partitionMapper,
      JobTaskMapper taskMapper,
      WorkerRegistryCache workerRegistryCache,
      JdbcTemplate jdbc) {
    this.launchService = launchService;
    this.dispatcher = dispatcher;
    this.waitingScheduler = waitingScheduler;
    this.taskExecutionService = taskExecutionService;
    this.partitionMapper = partitionMapper;
    this.taskMapper = taskMapper;
    this.workerRegistryCache = workerRegistryCache;
    this.jdbc = jdbc;
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
    jdbc.update(updateTenantQuotaPolicySql,
        highTenant);
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
    assertThat(taskExecutionService.assignWorker(highTenant, high.task().getId(), high.seed().workerCode()))
        .isNotNull();
    JobPartitionEntity runningPartition = partitionMapper.selectById(highTenant, high.partition().getId());
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
    }).when(instanceMapper).markRunning(any());

    assertThatThrownBy(() -> RlsTenantContextHolder.runWithTenant(tenant,
        () -> dispatcher.executeDispatch(job.partition(), job.task(), job.instance(), candidate())))
        .isInstanceOf(BizException.class);

    assertThat(instanceMapper.selectById(tenant, job.instance().getId()).getInstanceStatus())
        .isEqualTo("WAITING");
    assertThat(partitionMapper.selectById(tenant, job.partition().getId()).getPartitionStatus())
        .isEqualTo("WAITING");
    assertThat(taskMapper.selectById(tenant, job.task().getId()).getTaskStatus()).isEqualTo("CREATED");
    Long selectedOutboxEventCount =
        jdbc.queryForObject(selectCountSql, Long.class, tenant, job.task().getId());
    assertThat(selectedOutboxEventCount).isZero();
  }

  private WaitingJob launchWaiting(String tenant) {
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbc, tenant, "GENERAL", WORKER_GROUP, TriggerType.API, BIZ_DATE);
    var response = launchService.launch(request(tenant, seed));
    Long id = LaunchIntegrationFixture.selectDispatchedJobInstanceId(jdbc, tenant, response.instanceNo());
    JobInstanceEntity instance = instanceMapper.selectById(tenant, id);
    assertThat(instance.getInstanceStatus()).isEqualTo("WAITING");
    JobPartitionQuery partitionQuery = JobPartitionQuery.ofJobInstance(tenant, id);
    List<JobPartitionEntity> partitions = partitionMapper.selectByQuery(partitionQuery);
    JobPartitionEntity partition = partitions.getFirst();
    return new WaitingJob(seed, instance, partition,
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

  private record WaitingJob(WorkflowLaunchSeed seed, JobInstanceEntity instance,
      JobPartitionEntity partition, JobTaskEntity task) {}

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
    }).when(partitionThrottle).check(any(), any());
    doAnswer(invocation -> {
      ResourceSchedulingRequest schedulingRequest = invocation.getArgument(0);
      if (waitingSeed.jobCode().equals(schedulingRequest.getJobCode())) {
        waitingPid.set(jdbc.queryForObject(selectPgBackendPidSql, Integer.class));
        waitingEntered.countDown();
      }
      return invocation.callRealMethod();
    }).when(resourceScheduler).schedule(any());

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> launching = executor.submit(() -> launchService.launch(request(launchTenant, launchSeed)));
      assertThat(launchChecked.await(15, TimeUnit.SECONDS)).isTrue();
      Future<?> releasing = executor.submit(() -> RlsTenantContextHolder.runWithTenant(
          waitingTenant, () -> dispatcher.executeDispatch(partition, task, waitingInstance, candidate())));
      assertThat(waitingEntered.await(10, TimeUnit.SECONDS)).isTrue();
      await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
        Boolean selectedStatement =
            jdbc.queryForObject(selectStatementSql, Boolean.class, launchPid.get(), waitingPid.get());
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
      assertThat(taskMapper.selectById(waitingTenant, task.getId()).getTaskStatus()).isEqualTo("CREATED");
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

    jdbc.update(insertTenantQuotaPolicySql, tenant, cap == Cap.TENANT_JOB ? 1 : 0, cap == Cap.TENANT_PARTITION ? 1 : 0,
        cap == Cap.FAIR_SHARE_GROUP ? sharedGroup : null, cap == Cap.FAIR_SHARE_GROUP ? 1 : 0);
    jdbc.update(insertResourceQueueSql, tenant, cap == Cap.QUEUE_JOB ? 1 : 0, cap == Cap.QUEUE_PARTITION ? 1 : 0, WORKER_GROUP);
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
    TENANT_JOB, TENANT_PARTITION, QUEUE_JOB, QUEUE_PARTITION, FAIR_SHARE_GROUP
  }
}
