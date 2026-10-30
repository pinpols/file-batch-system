package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.constants.WorkerCapabilities;
import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.RetryScheduleStatus;
import io.github.pinpols.batch.common.enums.SystemPipelineTaskKeys;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskClaimCommand;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControllerApplicationService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskExecutionService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.RetryScheduleEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobPartitionQuery;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.domain.query.RetryScheduleQuery;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.BatchDaySettleScheduler;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.RetryScheduleMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试：任务重试流程。
 *
 * <p>初始化一个 {@code retry_policy=FIXED}、{@code retry_max_count=1} 的任务定义。 验证路径：
 *
 * <ol>
 *   <li>启动 → 创建任务。
 *   <li>认领 → 任务变为 RUNNING。
 *   <li>上报失败 → {@link RetryGovernanceService#scheduleRetryIfNecessary} 写入 状态为 {@code WAITING} 的
 *       {@code retry_schedule} 行。
 *   <li>{@link RetryGovernanceService#dispatchDueRetries} 处理到期重试并重新入队 分区（写入 event_type = worker
 *       type 的新 outbox_event）。
 * </ol>
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("任务失败后重试计划落库与到期重投递的端到端流程验证")
class JobRetryFlowIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 12, 25);

  @Autowired
  private WorkflowRunLaunchService workflowRunLaunchService;

  @Autowired
  private TaskExecutionService taskExecutionService;

  private final TaskControllerApplicationService taskControllerApplicationService;

  @Autowired
  private RetryGovernanceService retryGovernanceService;

  @Autowired
  private JobInstanceMapper jobInstanceMapper;

  @Autowired
  private JobPartitionMapper jobPartitionMapper;

  @Autowired
  private JobTaskMapper jobTaskMapper;

  @Autowired
  private RetryScheduleMapper retryScheduleMapper;

  private final WorkflowRunMapper workflowRunMapper;

  private final BatchDaySettleScheduler batchDaySettleScheduler;

  private final BatchDayInstanceMapper batchDayInstanceMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  JobRetryFlowIntegrationTest(
      TaskControllerApplicationService taskControllerApplicationService,
      WorkflowRunMapper workflowRunMapper,
      BatchDaySettleScheduler batchDaySettleScheduler,
      BatchDayInstanceMapper batchDayInstanceMapper) {
    this.taskControllerApplicationService = taskControllerApplicationService;
    this.workflowRunMapper = workflowRunMapper;
    this.batchDaySettleScheduler = batchDaySettleScheduler;
    this.batchDayInstanceMapper = batchDayInstanceMapper;
  }

  @Test
  @DisplayName("作业策略更新后任务仍按实例快照重试,到期重投递使分区重新入队")
  void taskFailure_withRetryPolicy_schedulesRetryAndDispatchDueRetries_requeuesPartition() {
    verifyRetryKeepsExecutionSnapshot(false);
  }

  @Test
  @DisplayName("dry-run 失败重试仍保留演练标记与实例快照")
  void dryRunTaskFailure_withRetryPolicy_preservesDryRunAcrossRetry() {
    verifyRetryKeepsExecutionSnapshot(true);
  }

  @Test
  @DisplayName("NONE 策略下结果不明的外部副作用进入死信且不会被自动重投")
  void nonIdempotentExternalOutcome_withRetryDisabled_requiresManualReconciliation() {
    final String updateJobDefinitionSql = """
      update batch.job_definition set retry_policy = 'NONE', retry_max_count = 0 where tenant_id = ?\s\
      and job_code = ?\
      """;
    final String selectCountSql = """
      select count(*) from batch.retry_schedule where tenant_id = ? and related_type = 'JOB_PARTITION'\s\
      and related_id = ?\
      """;
    final String selectCountSql2 = """
      select count(*) from batch.dead_letter_task where tenant_id = ? and source_type = 'JOB_PARTITION'\s\
      and source_id = ? and replay_status = 'NEW' and max_replay_count = 0 and next_replay_at is null\
      """;
    final String selectCountSql3 = """
      select count(*) from batch.dead_letter_task where tenant_id = ? and source_type = 'JOB_PARTITION'\s\
      and source_id = ? and replay_status = 'NEW' and replay_count = 0\
      """;

    LocalDate bizDate = BIZ_DATE.plusDays(Math.floorMod(System.nanoTime(), 10_000));
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, TENANT, "IMPORT", "DEFAULT", TriggerType.API, bizDate);
    jdbcTemplate.update(updateJobDefinitionSql, TENANT, seed.jobCode());

    LaunchRequest request = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(bizDate)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-non-idempotent-" + seed.requestId())
        .params(Map.of())
        .build();
    LaunchResponse response = workflowRunLaunchService.launch(request);
    Long jobInstanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, TENANT, response.instanceNo());
    JobTaskQuery taskQuery = JobTaskQuery.ofJobInstance(TENANT, jobInstanceId);
    List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(taskQuery);
    JobTaskEntity task = tasks.getFirst();
    JobPartitionQuery partitionQuery = JobPartitionQuery.ofJobInstance(TENANT, jobInstanceId);
    List<JobPartitionEntity> partitions = jobPartitionMapper.selectByQuery(partitionQuery);
    JobPartitionEntity partition = partitions.getFirst();

    taskExecutionService.assignWorker(TENANT, task.getId(), seed.workerCode());
    String invocationId =
        jobPartitionMapper.selectById(TENANT, partition.getId()).getCurrentInvocationId();
    TaskOutcomeCommand unknownOutcomeCommand = TaskOutcomeCommand.builder()
        .tenantId(TENANT)
        .taskId(task.getId())
        .success(false)
        .errorCode("EXTERNAL_OUTCOME_UNKNOWN")
        .errorMessage("remote side effect may have committed before response loss")
        .partitionInvocationId(invocationId)
        .build();
    taskExecutionService.applyTaskOutcome(unknownOutcomeCommand);

    Integer selectedRetryScheduleCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, partition.getId());
    assertThat(selectedRetryScheduleCount).isZero();
    Integer selectedDeadLetterTaskCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, partition.getId());
    assertThat(selectedDeadLetterTaskCount).isEqualTo(1);

    retryGovernanceService.autoRetryDueDeadLetters();
    Integer selectedDeadLetterTaskCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql3, Integer.class, TENANT, partition.getId());
    assertThat(selectedDeadLetterTaskCountRead2).isEqualTo(1);
    assertThat(jobPartitionMapper.selectById(TENANT, partition.getId()).getPartitionStatus())
        .isEqualTo("FAILED");
  }

  @Test
  @DisplayName("dry-run 重试成功与批日结算竞争时不解除正式 occurrence")
  void dryRunRetrySuccess_doesNotReleaseFormalOccurrenceDuringSettlementWait() throws Exception {
    final String updateTriggerRequestSql = """
        update batch.trigger_request set dry_run = true where tenant_id = ? and request_id = ?
    """;
    final String updateWorkerRegistrySql = """
        update batch.worker_registry set capability_tags = ?::jsonb where tenant_id = ? and worker_code = ?\
        """;
    final String updateJobDefinitionSql = """
        update batch.job_definition set retry_policy = 'FIXED', retry_max_count = 1 where tenant_id = ?\s\
        and job_code = ?\
        """;
    final String updateRetryScheduleSql = """
        update batch.retry_schedule set next_retry_at = now() - interval '1 second' where related_id = ?\s\
        and related_type = 'JOB_PARTITION' and retry_status = 'WAITING'\
        """;
    final String selectWorkflowCodeSql = """
        select workflow_code from batch.workflow_definition where id = ?
    """;
    final String insertScheduledOccurrenceSql = """
        insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,\s\
        schedule_version, calendar_code, scheduled_fire_time, biz_date, occurrence_status, request_id)\s\
        values (?, ?, ?, 1, 'JOB_CALENDAR', ?, ?, 'REQUESTED', ?)\
        """;
    final String insertBatchDayInstanceSql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at,\s\
        late_count, catchup_count, timezone_snapshot, dst_policy_snapshot, frozen, version, created_at,\s\
        updated_at, dry_run) values (?, 'JOB_CALENDAR', ?, 'CUTOFF', current_timestamp, 0, 0, 'UTC',\s\
        'gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET', false, 0, current_timestamp,\s\
        current_timestamp, false)\
        """;
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = 'JOB_CALENDAR'\s\
        and biz_date = ?\
        """;

    LocalDate bizDate = BIZ_DATE.plusDays(Math.floorMod(System.nanoTime(), 10_000));
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, TENANT, "IMPORT", "DEFAULT", TriggerType.SCHEDULED, bizDate);
    jdbcTemplate.update(updateTriggerRequestSql, TENANT, seed.requestId());
    jdbcTemplate.update(
        updateWorkerRegistrySql,
        JsonUtils.toJson(List.of(WorkerCapabilities.DRY_RUN_SAFE)),
        TENANT,
        seed.workerCode());
    jdbcTemplate.update(updateJobDefinitionSql, TENANT, seed.jobCode());

    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(bizDate)
        .triggerType(TriggerType.SCHEDULED)
        .requestId(seed.requestId())
        .traceId("trace-dry-run-retry-settlement-" + seed.requestId())
        .params(Map.of())
        .dryRun(true)
        .build();
    LaunchResponse response = workflowRunLaunchService.launch(launchRequest);
    Long jobInstanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, TENANT, response.instanceNo());
    JobInstanceEntity jobInstance = jobInstanceMapper.selectById(TENANT, jobInstanceId);
    JobTaskQuery taskQuery = JobTaskQuery.ofJobInstance(TENANT, jobInstanceId);
    List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(taskQuery);
    JobPartitionQuery partitionQuery = JobPartitionQuery.ofJobInstance(TENANT, jobInstanceId);
    List<JobPartitionEntity> partitions = jobPartitionMapper.selectByQuery(partitionQuery);
    assertThat(tasks).hasSize(1);
    assertThat(partitions).hasSize(1);
    JobTaskEntity task = tasks.get(0);
    JobPartitionEntity partition = partitions.get(0);

    taskExecutionService.assignWorker(TENANT, task.getId(), seed.workerCode());
    JobPartitionEntity firstAttempt = jobPartitionMapper.selectById(TENANT, partition.getId());
    TaskOutcomeCommand firstAttemptOutcome = TaskOutcomeCommand.builder()
        .tenantId(TENANT)
        .taskId(task.getId())
        .errorCode("SIMULATED_RETRYABLE_ERROR")
        .errorMessage("dry-run settlement race")
        .partitionInvocationId(firstAttempt.getCurrentInvocationId())
        .build();
    taskExecutionService.applyTaskOutcome(firstAttemptOutcome);

    jdbcTemplate.update(updateRetryScheduleSql, partition.getId());
    retryGovernanceService.dispatchDueRetries();
    EffectiveTaskConfig retryClaim = taskControllerApplicationService.claim(
        task.getId(), new TaskClaimCommand(TENANT, seed.workerCode(), null));

    String workflowCode = jdbcTemplate.queryForObject(
        selectWorkflowCodeSql, String.class, seed.workflowDefinitionId());
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        TENANT,
        seed.workflowDefinitionId(),
        workflowCode,
        Timestamp.from(Instant.parse("2026-12-25T09:00:00Z")),
        bizDate,
        seed.requestId());
    jdbcTemplate.update(insertBatchDayInstanceSql, TENANT, bizDate);

    try (ExecutorService executor = Executors.newFixedThreadPool(2);
        Connection fence = jdbcTemplate.getDataSource().getConnection()) {
      fence.setAutoCommit(false);
      acquireSettlementFence(fence, TENANT, "JOB_CALENDAR", bizDate);
      int blockerPid = backendPid(fence);
      Future<?> settlement = executor.submit(batchDaySettleScheduler::settle);
      awaitBlockedBy(blockerPid, 1);

      TaskOutcomeCommand retryOutcomeCommand = TaskOutcomeCommand.builder()
          .tenantId(TENANT)
          .taskId(task.getId())
          .success(true)
          .resultSummary("{}")
          .partitionInvocationId(retryClaim.partitionInvocationId())
          .build();
      Future<?> report =
          executor.submit(() -> taskExecutionService.applyTaskOutcome(retryOutcomeCommand));
      awaitBlockedBy(blockerPid, 2);
      fence.commit();
      report.get(10, TimeUnit.SECONDS);
      // settle() 会扫描共享集成测试环境中所有待处理的业务日。
      settlement.get(60, TimeUnit.SECONDS);
    }

    assertThat(jobInstanceMapper.selectById(TENANT, jobInstanceId).getInstanceStatus())
        .isEqualTo("SUCCESS_DRY_RUN");
    assertThat(
            workflowRunMapper.selectByNodeJobInstanceId(TENANT, jobInstanceId).getRunStatus())
        .isEqualTo("SUCCESS_DRY_RUN");
    assertThat(batchDayInstanceMapper.countBlockingScheduledOccurrences(
            TENANT, "JOB_CALENDAR", bizDate))
        .isOne();
    String selectedDayStatus =
        jdbcTemplate.queryForObject(selectDayStatusSql, String.class, TENANT, bizDate);
    assertThat(selectedDayStatus).isEqualTo("CUTOFF");
  }

  private void verifyRetryKeepsExecutionSnapshot(boolean dryRun) {
    final String updateTriggerRequestSql = """
      update batch.trigger_request set dry_run = true where tenant_id = ? and request_id = ?
  """;
    final String updateWorkerRegistrySql = """
      update batch.worker_registry set capability_tags = ?::jsonb where tenant_id = ? and worker_code = ?\
      """;
    final String updateJobDefinitionSql = """
      update batch.job_definition set retry_policy = 'FIXED', retry_max_count = 1, default_params =\s\
      '{"retrySnapshot":"v1"}'::jsonb where tenant_id = ? and job_code = ?\
      """;
    final String updateJobDefinitionSql2 = """
      update batch.job_definition set retry_policy = 'NONE', retry_max_count = 0, default_params =\s\
      '{"retrySnapshot":"v2"}'::jsonb, job_type = 'EXPORT', version = version + 1 where tenant_id = ?\s\
      and job_code = ?\
      """;
    final String updateRetryScheduleSql = """
        update batch.retry_schedule set next_retry_at = now() - interval '1 second' where related_id = ?\s\
        and related_type = 'JOB_PARTITION' and retry_status = 'WAITING'\
        """;

    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, TENANT, "IMPORT", "DEFAULT", TriggerType.API, BIZ_DATE);
    if (dryRun) {
      jdbcTemplate.update(updateTriggerRequestSql, TENANT, seed.requestId());
      jdbcTemplate.update(
          updateWorkerRegistrySql,
          JsonUtils.toJson(List.of(WorkerCapabilities.DRY_RUN_SAFE)),
          TENANT,
          seed.workerCode());
    }
    jdbcTemplate.update(updateJobDefinitionSql, TENANT, seed.jobCode());

    // 1) Launch
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-retry-" + seed.requestId())
        .params(Map.of())
        .dryRun(dryRun)
        .build();
    LaunchResponse response = workflowRunLaunchService.launch(launchRequest);
    assertThat(response.instanceNo()).isNotBlank();

    Long jobInstanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, TENANT, response.instanceNo());
    JobInstanceEntity jobInstance = jobInstanceMapper.selectById(TENANT, jobInstanceId);
    assertThat(jobInstance).isNotNull();
    assertThat(Boolean.TRUE.equals(jobInstance.getDryRun())).isEqualTo(dryRun);
    Map<?, ?> snapshot = JsonUtils.fromJson(jobInstance.getParamsSnapshot(), Map.class);
    Map<?, ?> executionPolicy = (Map<?, ?>) snapshot.get("executionPolicy");
    assertThat(executionPolicy.get("retryPolicy")).isEqualTo("FIXED");
    assertThat(executionPolicy.get("retryMaxCount")).isEqualTo(1);

    // 实例创建后管理员修改策略、参数及 Job 类型；已有任务重试必须继续使用已派发的快照。
    jdbcTemplate.update(updateJobDefinitionSql2, TENANT, seed.jobCode());

    Long taskInstanceId = jobInstance.getId();
    JobTaskQuery taskQuery = JobTaskQuery.ofJobInstance(TENANT, taskInstanceId);
    List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(taskQuery);
    assertThat(tasks).isNotEmpty();
    JobTaskEntity task = tasks.get(0);

    Long partitionInstanceId = jobInstance.getId();
    JobPartitionQuery partitionQuery = JobPartitionQuery.ofJobInstance(TENANT, partitionInstanceId);
    List<JobPartitionEntity> partitions = jobPartitionMapper.selectByQuery(partitionQuery);
    assertThat(partitions).isNotEmpty();
    JobPartitionEntity partition = partitions.get(0);

    // 2) Claim
    taskExecutionService.assignWorker(TENANT, task.getId(), seed.workerCode());

    // 3) 回报失败后应安排重试
    // 镜像生产:CLAIM 确立的 invocationId 随 report 回填(见 R3-P1-10 report invocation fence)。
    // partition 在 CLAIM 后才写入 current_invocation_id,故重读该行取回值。
    JobPartitionEntity claimedPartition = jobPartitionMapper.selectById(TENANT, partition.getId());
    TaskOutcomeCommand failureOutcome = TaskOutcomeCommand.builder()
        .tenantId(TENANT)
        .taskId(task.getId())
        .errorCode("SIMULATED_ERROR")
        .errorMessage("retry flow test")
        .partitionInvocationId(claimedPartition.getCurrentInvocationId())
        .build();
    taskExecutionService.applyTaskOutcome(failureOutcome);

    // 验证已为该分区创建了 WAITING 状态的 retry_schedule 行
    List<RetryScheduleEntity> retries = retryScheduleMapper.selectByQuery(new RetryScheduleQuery(
        TENANT,
        RetryScheduleStatus.WAITING.code(),
        BatchDateTimeSupport.utcNow().plusSeconds(3600),
        100));
    boolean retryFound = retries.stream()
        .anyMatch(r -> partition.getId().equals(r.getRelatedId())
            && "JOB_PARTITION".equals(r.getRelatedType()));
    assertThat(retryFound)
        .as("a WAITING retry_schedule row should exist for the failed partition")
        .isTrue();

    // 4) Make retry due immediately, then dispatch
    jdbcTemplate.update(updateRetryScheduleSql, partition.getId());

    long outboxBefore = countOutboxForTenant(TENANT);
    retryGovernanceService.dispatchDueRetries();
    long outboxAfter = countOutboxForTenant(TENANT);

    // 重新入队的分区应写入新的 Outbox 事件。
    assertThat(outboxAfter).isGreaterThan(outboxBefore);

    // 重投递后走生产 CLAIM 应用服务，断言返回的仍是首次派发时冻结的参数和系统 Pipeline 身份。
    EffectiveTaskConfig retryClaim = taskControllerApplicationService.claim(
        task.getId(), new TaskClaimCommand(TENANT, seed.workerCode(), null));
    assertThat(retryClaim.partitionInvocationId())
        .as(
            "a retried partition must receive a new invocation identity for PROCESS generation fencing")
        .isNotEqualTo(claimedPartition.getCurrentInvocationId());
    Map<?, ?> retryPayload = JsonUtils.fromJson(retryClaim.payload(), Map.class);
    assertThat(retryPayload.get("retrySnapshot")).isEqualTo("v1");
    assertThat(retryPayload.get(SystemPipelineTaskKeys.CODE)).isEqualTo("IMPORT");
    assertThat(retryPayload.get(SystemPipelineTaskKeys.VERSION)).isEqualTo(1);
    assertThat(Boolean.TRUE.equals(retryPayload.get("dryRun"))).isEqualTo(dryRun);
    assertThat(retryClaim.retryPolicy()).isEqualTo("FIXED");
    assertThat(retryClaim.retryMaxCount()).isEqualTo(1);

    TaskOutcomeCommand successfulOutcomeCommand = TaskOutcomeCommand.builder()
        .tenantId(TENANT)
        .taskId(task.getId())
        .success(true)
        .resultSummary("{}")
        .partitionInvocationId(retryClaim.partitionInvocationId())
        .build();
    taskExecutionService.applyTaskOutcome(successfulOutcomeCommand);

    JobInstanceEntity completedInstance = jobInstanceMapper.selectById(TENANT, jobInstanceId);
    assertThat(completedInstance.getInstanceStatus())
        .isEqualTo(dryRun ? "SUCCESS_DRY_RUN" : "SUCCESS");
    assertThat(
            workflowRunMapper.selectByNodeJobInstanceId(TENANT, jobInstanceId).getRunStatus())
        .isEqualTo(dryRun ? "SUCCESS_DRY_RUN" : "SUCCESS");
  }

  private long countOutboxForTenant(String tenantId) {
    final String selectCountSql = """
        select count(*) from batch.outbox_event where tenant_id = ?
    """;

    Long n = jdbcTemplate.queryForObject(selectCountSql, Long.class, tenantId);
    return n == null ? 0L : n;
  }

  private void acquireSettlementFence(
      Connection connection, String tenantId, String calendarCode, LocalDate bizDate)
      throws SQLException {
    final String selectAcquireBatchDaySettlementFenceSql = """
        select batch.acquire_batch_day_settlement_fence(?, ?, ?)
    """;

    try (PreparedStatement statement =
        connection.prepareStatement(selectAcquireBatchDaySettlementFenceSql)) {
      statement.setString(1, tenantId);
      statement.setString(2, calendarCode);
      statement.setObject(3, bizDate);
      statement.executeQuery().close();
    }
  }

  private int backendPid(Connection connection) throws SQLException {
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;

    try (var statement = connection.prepareStatement(selectPgBackendPidSql)) {
      try (var result = statement.executeQuery()) {
        if (!result.next()) throw new AssertionError("PostgreSQL backend PID was not returned");
        return result.getInt(1);
      }
    }
  }

  private void awaitBlockedBy(int blockerPid, int waiterCount) throws Exception {
    final String selectCountSql = """
        select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))
    """;

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Integer waiting = jdbcTemplate.queryForObject(selectCountSql, Integer.class, blockerPid);
      if (waiting != null && waiting >= waiterCount) return;
      Thread.sleep(10);
    }
    throw new AssertionError("Batch-day settlement did not reach its advisory-lock barrier");
  }
}
