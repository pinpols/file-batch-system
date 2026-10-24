package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.DeadLetterReplayStatus;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskExecutionService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobPartitionQuery;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * REQUIRES_NEW 事务边界集成测试。
 *
 * <p>验证使用 {@code Propagation.REQUIRES_NEW} 的方法其内部事务独立于外部事务：
 *
 * <ul>
 *   <li>内部事务提交后，即使外部事务回滚，内部写入仍然持久化。
 *   <li>内部事务抛异常回滚时，不影响外部事务已提交的数据。
 * </ul>
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("独立新事务传播边界的提交独立性验证,内部事务提交不受外部回滚影响且非法状态操作被拒绝")
class RequiresNewTransactionBoundaryIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 12, 24);

  @Autowired
  private WorkflowRunLaunchService workflowRunLaunchService;

  @Autowired
  private TaskExecutionService taskExecutionService;

  @Autowired
  private RetryGovernanceService retryGovernanceService;

  @Autowired
  private JobInstanceMapper jobInstanceMapper;

  @Autowired
  private JobPartitionMapper jobPartitionMapper;

  @Autowired
  private JobTaskMapper jobTaskMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private PlatformTransactionManager transactionManager;

  // ── retryTask: REQUIRES_NEW 内部提交独立于外部回滚 ────────────────────────

  @Test
  @DisplayName("外部事务回滚时重试的内部事务仍独立提交,派发事件数量增加且任务状态被重置为就绪")
  void retryTask_innerCommitSurvivesOuterRollback() {
    LaunchedJob job = launchAndFail("RETRY_TASK_BOUNDARY");

    long outboxBefore = countOutbox();

    // 在外部事务中调用 retryTask（REQUIRES_NEW），然后回滚外部事务
    TransactionTemplate outer = new TransactionTemplate(transactionManager);
    outer.execute(status -> {
      retryGovernanceService.retryTask(TENANT, job.taskId, "boundary-test-retry");
      // 外部事务人工回滚
      status.setRollbackOnly();
      return null;
    });

    // retryTask 的 REQUIRES_NEW 内部事务应已独立提交：
    // partition 被重置为 READY，outbox 多了一条派发事件
    long outboxAfter = countOutbox();
    assertThat(outboxAfter)
        .as("retryTask REQUIRES_NEW should commit outbox event despite outer rollback")
        .isGreaterThan(outboxBefore);

    JobTaskEntity taskAfter = jobTaskMapper.selectById(TENANT, job.taskId);
    assertThat(taskAfter.getStatus())
        .as("task should be reset to READY by retryTask")
        .isEqualTo(TaskStatus.READY.code());
  }

  // ── reclaimTask: REQUIRES_NEW 内部提交独立于外部回滚 ──────────────────────

  @Test
  @DisplayName("租约过期后回收的内部事务在外部事务回滚时仍独立提交,派发事件数量增加且任务状态被重置为就绪")
  void reclaimTask_innerCommitSurvivesOuterRollback() {
    LaunchedJob job = launchAndClaim("RECLAIM_BOUNDARY");
    // D4 修复：resetForDispatch 现在守护 lease_expire_at < now 防止 reclaim 抢答活 worker。
    // 测试场景模拟"lease 真实过期后被 scheduler 回收"——手动把 lease 过期掉。
    String expireJobPartitionLeaseSql = """
        update batch.job_partition
           set lease_expire_at = now() - interval '1 minute'
         where tenant_id = ?
           and id = (select job_partition_id from batch.job_task where id = ?)
        """;
    jdbcTemplate.update(expireJobPartitionLeaseSql, TENANT, job.taskId);

    long outboxBefore = countOutbox();

    TransactionTemplate outer = new TransactionTemplate(transactionManager);
    outer.execute(status -> {
      retryGovernanceService.reclaimTask(TENANT, job.taskId, "boundary-test-reclaim");
      status.setRollbackOnly();
      return null;
    });

    long outboxAfter = countOutbox();
    assertThat(outboxAfter)
        .as("reclaimTask REQUIRES_NEW should commit outbox event despite outer rollback")
        .isGreaterThan(outboxBefore);

    JobTaskEntity taskAfter = jobTaskMapper.selectById(TENANT, job.taskId);
    assertThat(taskAfter.getStatus())
        .as("task should be reset to READY by reclaimTask")
        .isEqualTo(TaskStatus.READY.code());
  }

  // ── replayDeadLetter: 终态拒绝记账独立于外部回滚 ────────────────────────

  @Test
  @DisplayName("终态父执行拒绝重放并保持业务状态,失败记账在外部事务回滚后仍保留")
  void shouldRetainReplayRejection_whenOuterTransactionRollsBack() {
    LaunchedJob job = launchAndExhaustRetries("REPLAY_DL_BOUNDARY");

    // 确认已产生死信
    Long deadLetterId = findDeadLetterForPartition(job.partitionId);
    assertThat(deadLetterId).as("dead letter should exist").isNotNull();

    long outboxBefore = countOutbox();

    TransactionTemplate outer = new TransactionTemplate(transactionManager);
    assertThatThrownBy(() -> outer.execute(status -> {
          status.setRollbackOnly();
          retryGovernanceService.replayDeadLetter(TENANT, deadLetterId);
          return null;
        }))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.partition.retry_execution_closed");

    long outboxAfter = countOutbox();
    assertThat(outboxAfter)
        .as("closed execution must not create a new dispatch")
        .isEqualTo(outboxBefore);

    // 拒绝记账独立提交；终态父执行不能被重放复活。
    String selectReplayStatusSql =
        """
        select replay_status from batch.dead_letter_task where id = ?
    """;
    String replayStatus = jdbcTemplate.queryForObject(
        selectReplayStatusSql,
        String.class,
        deadLetterId);
    assertThat(replayStatus).isEqualTo(DeadLetterReplayStatus.GIVE_UP.code());
    String selectReplayCountSql =
        """
        select replay_count from batch.dead_letter_task where tenant_id = ? and id = ?
    """;
    Integer selectedReplayCount =
        jdbcTemplate.queryForObject(selectReplayCountSql, Integer.class, TENANT, deadLetterId);
    assertThat(selectedReplayCount)
        .as("terminal rejection closes the dead letter without starting a replay attempt")
        .isZero();
    assertThat(jobInstanceMapper.selectById(TENANT, job.instanceId).getInstanceStatus())
        .isEqualTo(JobInstanceStatus.FAILED.code());
    assertThat(jobPartitionMapper.selectById(TENANT, job.partitionId).getPartitionStatus())
        .isEqualTo(PartitionStatus.FAILED.code());
    assertThat(jobTaskMapper.selectById(TENANT, job.taskId).getTaskStatus())
        .isEqualTo(TaskStatus.FAILED.code());
  }

  // ── retryTask: 非终态任务拒绝重试 ────────────────────────────────────────

  @Test
  @DisplayName("任务处于运行中非终态时重试被拒绝,并给出终态校验错误提示")
  void retryTask_rejectsNonTerminalStatus() {
    LaunchedJob job = launchAndClaim("RETRY_GUARD");

    // task 是 RUNNING，retryTask 应拒绝（只允许 FAILED/CANCELLED/TERMINATED）
    assertThatThrownBy(
            () -> retryGovernanceService.retryTask(TENANT, job.taskId, "boundary-reject"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.task.retry_not_terminal");
  }

  // ── reclaimTask: 非法状态拒绝 ────────────────────────────────────────────

  @Test
  @DisplayName("任务仍处于创建或就绪阶段时回收被拒绝,抛出状态非法异常")
  void reclaimTask_rejectsCreatedStatus() {
    LaunchedJob job = launchJob("RECLAIM_GUARD");

    // task 是 CREATED/READY，reclaimTask 应拒绝（只允许 RUNNING 或终态）
    assertThatThrownBy(
            () -> retryGovernanceService.reclaimTask(TENANT, job.taskId, "boundary-reject"))
        .isInstanceOf(IllegalStateException.class);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private record LaunchedJob(Long instanceId, Long partitionId, Long taskId, String workerCode) {}

  /** 启动任务并让其进入 FAILED 状态（走完 launch → claim → report failure）。 */
  private LaunchedJob launchAndFail(String prefix) {
    LaunchedJob job = launchAndClaim(prefix);
    TaskOutcomeCommand failureOutcome = TaskOutcomeCommand.builder()
        .tenantId(TENANT)
        .taskId(job.taskId)
        .errorCode("SIMULATED_ERROR")
        .errorMessage("boundary test")
        // 镜像生产:CLAIM 确立的 invocationId 随 report 回填(R3-P1-10 report invocation fence)。
        .partitionInvocationId(currentInvocationId(job.partitionId))
        .build();
    taskExecutionService.applyTaskOutcome(failureOutcome);
    return job;
  }

  /** 读回 partition CLAIM 时写入的 current_invocation_id,复现真 worker 在 report 携带该值。 */
  private String currentInvocationId(Long partitionId) {
    if (partitionId == null) {
      return null;
    }
    return jobPartitionMapper.selectById(TENANT, partitionId).getCurrentInvocationId();
  }

  /** 启动任务并让其进入 RUNNING 状态（走完 launch → claim）。 */
  private LaunchedJob launchAndClaim(String prefix) {
    LaunchedJob job = launchJob(prefix);
    taskExecutionService.assignWorker(TENANT, job.taskId, job.workerCode);
    return job;
  }

  /** 启动任务（READY 状态），不 claim。 */
  private LaunchedJob launchJob(String prefix) {
    return launchWorkflowJob(prefix, "FIXED", 1);
  }

  /** 启动任务，失败并立即进入死信（retry_policy=NONE）。 */
  private LaunchedJob launchAndExhaustRetries(String prefix) {
    LaunchedJob job = launchWorkflowJob(prefix, "NONE", 0);
    taskExecutionService.assignWorker(TENANT, job.taskId, job.workerCode);
    TaskOutcomeCommand exhaustOutcome = TaskOutcomeCommand.builder()
        .tenantId(TENANT)
        .taskId(job.taskId)
        .errorCode("EXHAUST_ERROR")
        .errorMessage("exhaust retries")
        .partitionInvocationId(currentInvocationId(job.partitionId))
        .build();
    taskExecutionService.applyTaskOutcome(exhaustOutcome);
    return job;
  }

  private LaunchedJob launchWorkflowJob(String prefix, String retryPolicy, int retryMaxCount) {
    String workerGroup = "WG_" + prefix;
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, TENANT, "IMPORT", workerGroup, TriggerType.API, BIZ_DATE);
    String updateJobDefinitionRetryPolicySql = """
        update batch.job_definition set retry_policy = ?, retry_max_count = ?
         where tenant_id = ? and job_code = ?
        """;
    jdbcTemplate.update(
        updateJobDefinitionRetryPolicySql, retryPolicy, retryMaxCount, TENANT, seed.jobCode());
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-" + prefix + "-" + seed.requestId())
        .params(Map.of())
        .build();
    var response = workflowRunLaunchService.launch(launchRequest);
    Long instanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, TENANT, response.instanceNo());
    JobInstanceEntity instance = jobInstanceMapper.selectById(TENANT, instanceId);
    List<JobPartitionEntity> partitions =
        jobPartitionMapper.selectByQuery(JobPartitionQuery.ofJobInstance(TENANT, instanceId));
    List<JobTaskEntity> tasks =
        jobTaskMapper.selectByQuery(JobTaskQuery.ofJobInstance(TENANT, instanceId));
    return new LaunchedJob(
        instanceId,
        partitions.isEmpty() ? null : partitions.get(0).getId(),
        tasks.get(0).getId(),
        seed.workerCode());
  }

  private Long findDeadLetterForPartition(Long partitionId) {
    String selectDeadLetterByPartitionSql = """
        select id from batch.dead_letter_task
         where tenant_id = ? and source_id = ?
           and source_type = 'JOB_PARTITION'
         order by id desc limit 1
        """;
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        selectDeadLetterByPartitionSql,
        TENANT,
        partitionId);
    return rows.isEmpty() ? null : ((Number) rows.get(0).get("id")).longValue();
  }

  private long countOutbox() {
    String countOutboxEventsSql = """
        select count(*) from batch.outbox_event where tenant_id = ?
    """;
    Long n = jdbcTemplate.queryForObject(countOutboxEventsSql, Long.class, TENANT);
    return n == null ? 0L : n;
  }
}
