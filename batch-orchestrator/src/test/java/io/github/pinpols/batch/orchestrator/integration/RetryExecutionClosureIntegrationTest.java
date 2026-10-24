package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskExecutionService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.param.AssignWorkerParam;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("真实 PostgreSQL 重试预算、父级终态、暂停、重放回滚和并发收口")
@ExtendWith(OutputCaptureExtension.class)
class RetryExecutionClosureIntegrationTest extends AbstractIntegrationTest {
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 10, 16);

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private WorkflowRunLaunchService launchService;

  @Autowired
  private TaskExecutionService execution;

  @Autowired
  private RetryGovernanceService retries;

  @Autowired
  private JobTaskMapper tasks;

  @Autowired
  private JobPartitionMapper partitions;

  @Autowired
  private JobInstanceMapper instances;

  @Autowired
  private WorkflowRunMapper workflows;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @MockitoSpyBean
  private TaskDispatchOutboxService dispatch;

  @ParameterizedTest
  @CsvSource({"NONE,0", "FIXED,0", "FIXED,1"})
  @DisplayName("NONE、零预算和耗尽都只形成最终死信，重复回报和调度不重置预算")
  void shouldCloseFailureWithoutSecondBudget_whenAutomaticRetriesEnd(String policy, int budget) {
    Scenario scenario = launch(policy, budget);
    fail(scenario);
    if (budget > 0) {
      makeRetriesDue(scenario);
      retries.dispatchDueRetries();
      fail(scenario);
    }
    Map<String, Object> deadLetter = deadLetter(scenario);
    assertThat(deadLetter.get("max_replay_count")).isEqualTo(0);
    assertThat(deadLetter.get("next_replay_at")).isNull();
    assertThat(instanceStatus(scenario)).isEqualTo("FAILED");
    assertThat(workflowStatus(scenario)).isEqualTo("FAILED");
    List<Map<String, Object>> before = executionRows(scenario);
    long outboxBefore = outboxCount(scenario);
    // 重投旧报告和运行到期扫描，不能产生新的业务执行/死信。
    execution.applyTaskOutcome(failure(scenario));
    retries.autoRetryDueDeadLetters();
    retries.autoRetryDueDeadLetters();
    assertThat(executionRows(scenario)).isEqualTo(before);
    assertThat(outboxCount(scenario)).isEqualTo(outboxBefore);
    assertThat(jdbc.queryForObject(
            "select count(*) from batch.dead_letter_task where tenant_id = ?",
            Long.class,
            scenario.tenant()))
        .isEqualTo(1L);
    assertThat(tasks.selectForWorkerClaim(scenario.tenant(), scenario.taskId())).isNull();
  }

  @Test
  @DisplayName("有效预算内失败后能重新认领并成功完成，保持原日期和快照")
  void shouldFinishNormally_whenRetrySucceedsWithinBudget() {
    Scenario scenario = launch("FIXED", 1);
    fail(scenario);
    assertThat(instanceStatus(scenario)).isEqualTo("RUNNING");
    String snapshot = jdbc.queryForObject(
        "select params_snapshot::text from batch.job_instance where id = ?",
        String.class,
        scenario.instanceId());
    makeRetriesDue(scenario);
    retries.dispatchDueRetries();
    claim(scenario);
    TaskOutcomeCommand successfulRetry = TaskOutcomeCommand.builder()
        .tenantId(scenario.tenant())
        .taskId(scenario.taskId())
        .workerId(scenario.worker())
        .success(true)
        .partitionInvocationId(partition(scenario).getCurrentInvocationId())
        .build();
    execution.applyTaskOutcome(successfulRetry);
    assertThat(instanceStatus(scenario)).isEqualTo("SUCCESS");
    assertThat(workflowStatus(scenario)).isEqualTo("SUCCESS");
    assertThat(jdbc.queryForObject(
            "select params_snapshot::text from batch.job_instance where id = ?",
            String.class,
            scenario.instanceId()))
        .isEqualTo(snapshot);
    assertThat(jdbc.queryForObject(
            "select biz_date from batch.job_instance where id = ?",
            LocalDate.class,
            scenario.instanceId()))
        .isEqualTo(BIZ_DATE);
    assertThat(jdbc.queryForObject(
            "select count(*) from batch.dead_letter_task where tenant_id = ?",
            Long.class,
            scenario.tenant()))
        .isZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SUCCESS",
        "FAILED",
        "PARTIAL_FAILED",
        "CANCELLED",
        "TERMINATED",
        "SUCCESS_DRY_RUN",
        "FAILED_DRY_RUN"
      })
  @DisplayName("数据库认领读取、认领 CAS 和两种复位都拒绝已关闭的父 Job")
  void shouldFenceAllTaskEntryPoints_whenJobCannotExecute(String status) {
    Scenario scenario = launch("FIXED", 1);
    jdbc.update(
        "update batch.job_instance set instance_status = ? where id = ?",
        status,
        scenario.instanceId());
    assertEntryPointsClosed(scenario);
  }

  @ParameterizedTest
  @ValueSource(strings = {"SUCCESS", "FAILED", "TERMINATED", "SUCCESS_DRY_RUN", "FAILED_DRY_RUN"})
  @DisplayName("父 Job 仍运行时也不能绕过关联 Workflow 的终态保护")
  void shouldFenceAllTaskEntryPoints_whenWorkflowCannotExecute(String status) {
    Scenario scenario = launch("FIXED", 1);
    jdbc.update(
        "update batch.workflow_run set run_status = ? where id = ?", status, scenario.workflowId());
    assertEntryPointsClosed(scenario);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("暂停只停止新派发，已派发任务仍能 CLAIM 且不能新建重试派发")
  void shouldKeepInflightClaimAndDeferRequeue_whenExecutionIsPaused(boolean pauseJob) {
    Scenario scenario = launch("FIXED", 1);
    if (pauseJob) {
      jdbc.update(
          "update batch.job_instance set instance_status = 'PAUSED' where id = ?",
          scenario.instanceId());
    } else {
      jdbc.update(
          "update batch.workflow_run set run_status = 'PAUSED' where id = ?",
          scenario.workflowId());
    }
    JobTaskEntity task = tasks.selectById(scenario.tenant(), scenario.taskId());
    JobPartitionEntity partition = partition(scenario);
    assertThat(tasks.selectForWorkerClaim(scenario.tenant(), task.getId())).isNotNull();
    assertThat(tasks.resetForRetry(scenario.tenant(), task.getId(), "READY", task.getVersion()))
        .isZero();
    assertThat(partitions.resetForDispatch(
            scenario.tenant(), partition.getId(), "READY", partition.getVersion()))
        .isZero();
    claim(scenario);
  }

  @Test
  @DisplayName("已有到期重试在 Workflow 终结后标记失败，不会永久留 WAITING 或重开任务")
  void shouldCloseDueRetry_whenParentWorkflowTerminates() {
    Scenario scenario = launch("FIXED", 1);
    fail(scenario);
    jdbc.update(
        "update batch.workflow_run set run_status = 'TERMINATED' where id = ?",
        scenario.workflowId());
    makeRetriesDue(scenario);
    List<Map<String, Object>> before = executionRows(scenario);
    long outboxBefore = outboxCount(scenario);
    retries.dispatchDueRetries();
    retries.dispatchDueRetries();
    assertThat(retryStatus(scenario)).isEqualTo("FAILED");
    assertThat(executionRows(scenario)).isEqualTo(before);
    assertThat(outboxCount(scenario)).isEqualTo(outboxBefore);
  }

  @Test
  @DisplayName("暂停不消耗到期重试，恢复后同一计划继续派发")
  void shouldRetainDueRetryIdentity_whenWorkflowPausesAndResumes() {
    Scenario scenario = launch("FIXED", 1);
    fail(scenario);
    makeRetriesDue(scenario);
    jdbc.update(
        "update batch.workflow_run set run_status = 'PAUSED' where id = ?", scenario.workflowId());
    List<Map<String, Object>> before = executionRows(scenario);
    retries.dispatchDueRetries();
    assertThat(retryStatus(scenario)).isEqualTo("WAITING");
    assertThat(executionRows(scenario)).isEqualTo(before);
    jdbc.update(
        "update batch.workflow_run set run_status = 'RUNNING' where id = ?", scenario.workflowId());
    retries.dispatchDueRetries();
    assertThat(retryStatus(scenario)).isEqualTo("SUCCESS");
    assertThat(tasks.selectById(scenario.tenant(), scenario.taskId()).getTaskStatus())
        .isEqualTo("READY");
  }

  @Test
  @DisplayName("重放在 Outbox 写入失败时回滚所有业务复位，失败次数和原因仍提交")
  void shouldPersistReplayFailureWithoutPartialRequeue_whenOutboxWriteFails() {
    Scenario scenario = launch("NONE", 0);
    Long deadLetterId = seedDueDeadLetter(scenario);
    List<Map<String, Object>> before = executionRows(scenario);
    long outboxBefore = outboxCount(scenario);
    TaskDispatchOutboxService target =
        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(dispatch);
    doThrow(new IllegalStateException("SIM_REPLAY_OUTBOX_FAILED"))
        .when(target)
        .writeDispatchEvent(
            argThat(instance -> scenario.tenant().equals(instance.getTenantId())),
            any(),
            any(),
            any(),
            any(),
            any());

    assertThatThrownBy(() -> retries.replayDeadLetter(scenario.tenant(), deadLetterId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("SIM_REPLAY_OUTBOX_FAILED");

    assertThat(executionRows(scenario)).isEqualTo(before);
    assertThat(outboxCount(scenario)).isEqualTo(outboxBefore);
    Map<String, Object> deadLetter = deadLetter(scenario);
    assertThat(deadLetter.get("replay_status")).isEqualTo("FAILED");
    assertThat(deadLetter.get("replay_count")).isEqualTo(1);
    assertThat(deadLetter.get("last_replay_result")).isEqualTo("SIM_REPLAY_OUTBOX_FAILED");
  }

  @Test
  @DisplayName("失效到期死信转 GIVE_UP，保留现场且不复活终态执行")
  void shouldCloseDueDeadLetter_whenSourceExecutionHasEnded() {
    Scenario scenario = launch("NONE", 0);
    fail(scenario);
    jdbc.update(
        "update batch.dead_letter_task set max_replay_count = 3, next_replay_at = now() - interval '1 second' where tenant_id = ?",
        scenario.tenant());
    List<Map<String, Object>> before = executionRows(scenario);
    long outboxBefore = outboxCount(scenario);
    retries.autoRetryDueDeadLetters();
    retries.autoRetryDueDeadLetters();
    assertThat(deadLetter(scenario).get("replay_status")).isEqualTo("GIVE_UP");
    assertThat(executionRows(scenario)).isEqualTo(before);
    assertThat(outboxCount(scenario)).isEqualTo(outboxBefore);
  }

  @Test
  @Timeout(30)
  @DisplayName("重试等候实例围栏期间父级先终结，锁后重读拒绝重开")
  void shouldRejectRequeueAfterWaitingForFence_whenTerminalTransactionCommits() throws Exception {
    Scenario scenario = launch("FIXED", 1);
    fail(scenario);
    makeRetriesDue(scenario);
    long outboxBefore = outboxCount(scenario);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    CompletableFuture<Void> retry = transaction.execute(status -> {
      instances.acquireInstanceAdvisoryLock(scenario.tenant(), scenario.instanceId());
      jdbc.update(
          "update batch.job_instance set instance_status = 'FAILED' where id = ?",
          scenario.instanceId());
      CompletableFuture<Void> future = CompletableFuture.runAsync(retries::dispatchDueRetries);
      org.awaitility.Awaitility.await()
          .atMost(10, TimeUnit.SECONDS)
          .until(() -> jdbc.queryForObject(
                  "select count(*) from pg_locks where locktype = 'advisory' and not granted",
                  Long.class)
              > 0);
      return future;
    });
    retry.get(10, TimeUnit.SECONDS);
    assertThat(retryStatus(scenario)).isEqualTo("FAILED");
    assertThat(instanceStatus(scenario)).isEqualTo("FAILED");
    assertThat(tasks.selectById(scenario.tenant(), scenario.taskId()).getTaskStatus())
        .isEqualTo("FAILED");
    assertThat(outboxCount(scenario)).isEqualTo(outboxBefore);
  }

  @Test
  @DisplayName("事务回滚不能输出已创建死信的幽灵告警，提交后只记一次")
  void shouldLogDeadLetterOnlyAfterCommit_whenOutcomeTransactionRollsBack(CapturedOutput captured) {
    Scenario scenario = launch("NONE", 0);
    claim(scenario);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
          execution.applyTaskOutcome(failure(scenario));
          throw new IllegalStateException("SIM_REPORT_ROLLBACK");
        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("SIM_REPORT_ROLLBACK");
    String warning = "dead letter created: tenantId=" + scenario.tenant();
    assertThat(jdbc.queryForObject(
            "select count(*) from batch.dead_letter_task where tenant_id = ?",
            Long.class,
            scenario.tenant()))
        .isZero();
    assertThat(captured.getAll()).doesNotContain(warning);

    execution.applyTaskOutcome(failure(scenario));

    assertThat(captured.getAll().split(java.util.regex.Pattern.quote(warning), -1))
        .hasSize(2);
  }

  private void assertEntryPointsClosed(Scenario scenario) {
    JobTaskEntity task = tasks.selectById(scenario.tenant(), scenario.taskId());
    JobPartitionEntity partition = partition(scenario);
    assertThat(tasks.selectForWorkerClaim(scenario.tenant(), task.getId())).isNull();
    assertThat(tasks.assignWorker(AssignWorkerParam.builder()
            .tenantId(scenario.tenant())
            .id(task.getId())
            .assignedWorkerCode(scenario.worker())
            .expectedAssignedWorkerCode(task.getAssignedWorkerCode())
            .taskStatus("RUNNING")
            .readyStatus("READY")
            .expectedVersion(task.getVersion())
            .build()))
        .isNull();
    assertThat(tasks.resetForRetry(scenario.tenant(), task.getId(), "READY", task.getVersion()))
        .isZero();
    assertThat(partitions.resetForDispatch(
            scenario.tenant(), partition.getId(), "READY", partition.getVersion()))
        .isZero();
  }

  private Scenario launch(String retryPolicy, int retryBudget) {
    String tenant = "retry-" + UUID.randomUUID().toString().substring(0, 12);
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbc, tenant, "IMPORT", "DEFAULT", TriggerType.API, BIZ_DATE);
    jdbc.update(
        "update batch.job_definition set retry_policy = ?, retry_max_count = ? where tenant_id = ? and job_code = ?",
        retryPolicy,
        retryBudget,
        tenant,
        seed.jobCode());
    LaunchResponse response = launchService.launch(LaunchRequest.builder()
        .tenantId(tenant)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-" + tenant)
        .params(Map.of())
        .build());
    Long instanceId =
        LaunchIntegrationFixture.selectDispatchedJobInstanceId(jdbc, tenant, response.instanceNo());
    List<JobTaskEntity> found =
        tasks.selectByQuery(JobTaskQuery.ofJobInstance(tenant, instanceId));
    assertThat(found).hasSize(1);
    JobTaskEntity task = found.getFirst();
    WorkflowRunEntity workflow = workflows.selectByNodeJobInstanceId(tenant, instanceId);
    return new Scenario(
        tenant,
        instanceId,
        task.getId(),
        task.getJobPartitionId(),
        workflow.getId(),
        seed.workerCode());
  }

  private void claim(Scenario scenario) {
    assertThat(execution
            .assignWorker(scenario.tenant(), scenario.taskId(), scenario.worker())
            .getTaskStatus())
        .isEqualTo("RUNNING");
  }

  private void fail(Scenario scenario) {
    claim(scenario);
    execution.applyTaskOutcome(failure(scenario));
  }

  private TaskOutcomeCommand failure(Scenario scenario) {
    return TaskOutcomeCommand.builder()
        .tenantId(scenario.tenant())
        .taskId(scenario.taskId())
        .workerId(scenario.worker())
        .success(false)
        .errorCode("SIM_SYSTEM_FAILURE")
        .errorMessage("failure")
        .partitionInvocationId(partition(scenario).getCurrentInvocationId())
        .build();
  }

  private JobPartitionEntity partition(Scenario scenario) {
    return partitions.selectById(scenario.tenant(), scenario.partitionId());
  }

  private void makeRetriesDue(Scenario scenario) {
    jdbc.update(
        "update batch.retry_schedule set next_retry_at = now() - interval '1 second' where tenant_id = ?",
        scenario.tenant());
  }

  private String retryStatus(Scenario scenario) {
    return jdbc.queryForObject(
        "select retry_status from batch.retry_schedule where tenant_id = ?",
        String.class,
        scenario.tenant());
  }

  private String instanceStatus(Scenario scenario) {
    return instances.selectById(scenario.tenant(), scenario.instanceId()).getInstanceStatus();
  }

  private String workflowStatus(Scenario scenario) {
    return workflows.selectById(scenario.tenant(), scenario.workflowId()).getRunStatus();
  }

  private long outboxCount(Scenario scenario) {
    return jdbc.queryForObject(
        "select count(*) from batch.outbox_event where tenant_id = ?",
        Long.class,
        scenario.tenant());
  }

  private Map<String, Object> deadLetter(Scenario scenario) {
    return jdbc.queryForMap(
        "select * from batch.dead_letter_task where tenant_id = ?", scenario.tenant());
  }

  private List<Map<String, Object>> executionRows(Scenario scenario) {
    return jdbc.queryForList("""
        select 'task' as kind, id, task_status as status, version from batch.job_task where tenant_id = ?
        union all select 'partition', id, partition_status, version from batch.job_partition where tenant_id = ?
        union all select 'step', id, step_status, version from batch.job_step_instance where tenant_id = ?
        order by kind, id
        """, scenario.tenant(), scenario.tenant(), scenario.tenant());
  }

  private Long seedDueDeadLetter(Scenario scenario) {
    return jdbc.queryForObject("""
        insert into batch.dead_letter_task (tenant_id, source_type, source_id, dead_letter_reason,
          payload_ref, replay_status, replay_count, max_replay_count, next_replay_at, error_class)
        values (?, 'JOB_PARTITION', ?, 'test', 'test', 'NEW', 0, 3, now() - interval '1 second', 'SYSTEM')
        returning id
        """, Long.class, scenario.tenant(), scenario.partitionId());
  }

  private record Scenario(
      String tenant,
      Long instanceId,
      Long taskId,
      Long partitionId,
      Long workflowId,
      String worker) {}
}
