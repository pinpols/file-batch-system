package io.github.pinpols.batch.orchestrator.integration;

import io.github.pinpols.batch.orchestrator.application.service.governance.DeadLetterReplayInput;


import io.github.pinpols.batch.orchestrator.application.engine.command.TaskDispatchEventCommand;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import io.github.pinpols.batch.orchestrator.application.service.governance.CompensationService;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.domain.command.CompensationSubmitCommand;
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
import java.util.regex.Pattern;
import org.awaitility.Awaitility;
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
import org.springframework.test.util.AopTestUtils;
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
  private CompensationService compensation;

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
    final String selectCountSql = """
        select count(*) from batch.dead_letter_task where tenant_id = ?
    """;

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
    Long selectedDeadLetterTaskCount =
        jdbc.queryForObject(selectCountSql, Long.class, scenario.tenant());
    assertThat(selectedDeadLetterTaskCount).isEqualTo(1L);
    assertThat(tasks.selectForWorkerClaim(scenario.tenant(), scenario.taskId())).isNull();
  }

  @Test
  @DisplayName("有效预算内失败后能重新认领并成功完成，保持原日期和快照")
  void shouldFinishNormally_whenRetrySucceedsWithinBudget() {
    final String selectParamsSnapshotSql = """
        select params_snapshot::text from batch.job_instance where id = ?
    """;
    final String selectBizDateSql = """
        select biz_date from batch.job_instance where id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.dead_letter_task where tenant_id = ?
    """;

    Scenario scenario = launch("FIXED", 1);
    fail(scenario);
    assertThat(instanceStatus(scenario)).isEqualTo("RUNNING");
    String snapshot = jdbc.queryForObject(
        selectParamsSnapshotSql,
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
    String selectedParamsSnapshot =
        jdbc.queryForObject(selectParamsSnapshotSql, String.class, scenario.instanceId());
    assertThat(selectedParamsSnapshot).isEqualTo(snapshot);
    LocalDate selectedBizDate =
        jdbc.queryForObject(selectBizDateSql, LocalDate.class, scenario.instanceId());
    assertThat(selectedBizDate).isEqualTo(BIZ_DATE);
    Long selectedDeadLetterTaskCount =
        jdbc.queryForObject(selectCountSql, Long.class, scenario.tenant());
    assertThat(selectedDeadLetterTaskCount).isZero();
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
    final String updateJobInstanceSql = """
        update batch.job_instance set instance_status = ? where id = ?
    """;

    Scenario scenario = launch("FIXED", 1);
    jdbc.update(
        updateJobInstanceSql,
        status,
        scenario.instanceId());
    assertEntryPointsClosed(scenario);
  }

  @ParameterizedTest
  @ValueSource(strings = {"SUCCESS", "FAILED", "TERMINATED", "SUCCESS_DRY_RUN", "FAILED_DRY_RUN"})
  @DisplayName("父 Job 仍运行时也不能绕过关联 Workflow 的终态保护")
  void shouldFenceAllTaskEntryPoints_whenWorkflowCannotExecute(String status) {
    final String updateWorkflowRunSql = """
        update batch.workflow_run set run_status = ? where id = ?
    """;

    Scenario scenario = launch("FIXED", 1);
    jdbc.update(
        updateWorkflowRunSql, status, scenario.workflowId());
    assertEntryPointsClosed(scenario);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("暂停阻止普通重排队，但已派发任务仍能 CLAIM 与租约回收")
  void shouldKeepInflightClaimAndDeferRequeue_whenExecutionIsPaused(boolean pauseJob) {
    final String updateJobInstanceSql = """
        update batch.job_instance set instance_status = 'PAUSED' where id = ?
    """;
    final String updateWorkflowRunSql = """
        update batch.workflow_run set run_status = 'PAUSED' where id = ?
    """;
    final String updateJobPartitionSql = """
        update batch.job_partition set lease_expire_at = current_timestamp - interval '1 second'\s\
        where tenant_id = ? and id = ?\
        """;

    Scenario scenario = launch("FIXED", 1);
    if (pauseJob) {
      jdbc.update(
          updateJobInstanceSql,
          scenario.instanceId());
    } else {
      jdbc.update(
          updateWorkflowRunSql,
          scenario.workflowId());
    }
    JobTaskEntity claimableTask = tasks.selectById(scenario.tenant(), scenario.taskId());
    assertThat(tasks.selectForWorkerClaim(scenario.tenant(), claimableTask.getId())).isNotNull();
    claim(scenario);
    jdbc.update(
        updateJobPartitionSql,
        scenario.tenant(),
        scenario.partitionId());
    JobTaskEntity task = tasks.selectById(scenario.tenant(), scenario.taskId());
    JobPartitionEntity partition = partition(scenario);
    assertThat(tasks.selectForWorkerClaim(scenario.tenant(), task.getId())).isNotNull();
    assertThat(tasks.resetForRetry(scenario.tenant(), task.getId(), "READY", task.getVersion()))
        .isZero();
    assertThat(partitions.resetForDispatch(
            scenario.tenant(), partition.getId(), "READY", partition.getVersion()))
        .isZero();

    assertThat(partitions.resetForLeaseReclaim(
            scenario.tenant(), partition.getId(), "READY", partition.getVersion()))
        .isOne();
    assertThat(tasks.resetForLeaseReclaim(scenario.tenant(), task.getId(), "READY", task.getVersion()))
        .isOne();
  }

  @Test
  @DisplayName("真实 PostgreSQL 查询到未知副作用错误后拒绝活动 Workflow 的人工分区重试")
  void shouldRejectManualPartitionRetry_whenTaskExternalOutcomeIsUnknown() {
    final String updateJobTaskSql = """
        update batch.job_task set task_status = 'FAILED', error_code = ?, error_message =\s\
        'external effect may have committed', finished_at = now(), version = version + 1 where tenant_id = ?\s\
        and id = ?\
        """;

    Scenario scenario = launch("FIXED", 1);
    jdbc.update(
        updateJobTaskSql,
        TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN,
        scenario.tenant(),
        scenario.taskId());
    List<Map<String, Object>> before = executionRows(scenario);
    long outboxBefore = outboxCount(scenario);

    assertThatThrownBy(() -> retries.retryPartition(
            scenario.tenant(), scenario.partitionId(), "manual-unknown-outcome-retry"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.task.retry_unknown_outcome_requires_resolution");

    assertThat(executionRows(scenario)).isEqualTo(before);
    assertThat(tasks.selectById(scenario.tenant(), scenario.taskId()).getErrorCode())
        .isEqualTo(TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN);
    assertThat(outboxCount(scenario)).isEqualTo(outboxBefore);
    assertThat(instanceStatus(scenario)).isEqualTo("RUNNING");
    assertThat(workflowStatus(scenario)).isEqualTo("RUNNING");
  }

  @Test
  @DisplayName("已审批死信重放不能绕过外部 UNKNOWN，保留审计且不产生派发副作用")
  void shouldRejectDeadLetterReplay_whenTaskExternalOutcomeIsUnknown() {
    final String updateJobTaskSql = """
        update batch.job_task
        set task_status = 'FAILED', error_code = ?,
            error_message = 'external effect may have committed', finished_at = now(),
            version = version + 1
        where tenant_id = ? and id = ?
        """;
    final String updateJobPartitionSql = """
        update batch.job_partition
        set partition_status = 'FAILED', version = version + 1
        where tenant_id = ? and id = ?
        """;
    final String updateDeadLetterTaskSql = """
        update batch.dead_letter_task
        set max_replay_count = 0, next_replay_at = null
        where tenant_id = ? and id = ?
        """;
    final String selectCountSql = """
            select count(*) from batch.job_execution_log
            where tenant_id = ?
              and log_type = 'AUDIT'
              and message = 'DEAD_LETTER_REPLAY'
              and detail_ref = 'dead_letter_task'
              and extra_json->>'deadLetterTaskId' = ?
            """;

    Scenario scenario = launch("FIXED", 1);
    jdbc.update(
        updateJobTaskSql,
        TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN,
        scenario.tenant(),
        scenario.taskId());
    jdbc.update(
        updateJobPartitionSql,
        scenario.tenant(),
        scenario.partitionId());
    Long deadLetterId = seedDueDeadLetter(scenario);
    jdbc.update(
        updateDeadLetterTaskSql,
        scenario.tenant(),
        deadLetterId);
    List<Map<String, Object>> before = executionRows(scenario);
    long outboxBefore = outboxCount(scenario);

    assertThatThrownBy(() -> retries.replayDeadLetter(
            scenario.tenant(), deadLetterId, "operator-1", "reconcile uncertain result", "key-1"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.task.retry_unknown_outcome_requires_resolution");

    assertThat(executionRows(scenario)).isEqualTo(before);
    assertThat(outboxCount(scenario)).isEqualTo(outboxBefore);
    assertThat(tasks.selectById(scenario.tenant(), scenario.taskId()).getErrorCode())
        .isEqualTo(TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN);
    assertThat(deadLetter(scenario).get("replay_status")).isEqualTo("FAILED");
    assertThat(deadLetter(scenario).get("replay_count")).isEqualTo(1);
    assertThat(deadLetter(scenario).get("next_replay_at")).isNull();
    Long selectedJobExecutionLogCount =
        jdbc.queryForObject(selectCountSql, Long.class, scenario.tenant(), deadLetterId.toString());
    assertThat(selectedJobExecutionLogCount).isEqualTo(1L);
  }

  @Test
  @DisplayName("核实外部动作未执行时必须通过获批补偿创建新 Workflow")
  void shouldRejectDirectDeadLetterReplay_whenExternalActionWasVerifiedNotExecuted() {
    final String updateJobTaskSql = """
        update batch.job_task set task_status = 'FAILED', error_code = ?, error_message =\s\
        'external action was not executed', finished_at = now(), version = version + 1 where tenant_id = ?\s\
        and id = ?\
        """;
    final String updateJobPartitionSql = """
        update batch.job_partition set partition_status = 'FAILED', version = version + 1 where tenant_id = ?\s\
        and id = ?\
        """;
    final String updateDeadLetterTaskSql = """
        update batch.dead_letter_task set max_replay_count = 0, next_replay_at = null where tenant_id = ?\s\
        and id = ?\
        """;

    Scenario scenario = launch("FIXED", 1);
    jdbc.update(
        updateJobTaskSql,
        TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN,
        scenario.tenant(),
        scenario.taskId());
    jdbc.update(
        updateJobPartitionSql,
        scenario.tenant(),
        scenario.partitionId());
    Long deadLetterId = seedDueDeadLetter(scenario);
    jdbc.update(
        updateDeadLetterTaskSql,
        scenario.tenant(),
        deadLetterId);
    long outboxBefore = outboxCount(scenario);

    assertThatThrownBy(() -> retries.replayDeadLetter(
            DeadLetterReplayInput.builder()
                .tenantId(scenario.tenant())
                .deadLetterTaskId(deadLetterId)
                .operatorId("operator-1")
                .reason("external system audit confirmed no action")
                .idempotencyKey("verified-retry-1")
                .externalActionVerifiedNotExecuted(true)
                .externalOutcomeEvidenceReference("incident://ops/INC-2048")
                .build()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.dead_letter.external_outcome_resolution_requires_compensation");

    JobTaskEntity unchanged = tasks.selectById(scenario.tenant(), scenario.taskId());
    assertThat(unchanged.getTaskStatus()).isEqualTo("FAILED");
    assertThat(unchanged.getErrorCode()).isEqualTo(TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN);
    assertThat(unchanged.getExternalOutcomeResolutionVersion()).isNull();
    assertThat(outboxCount(scenario)).isEqualTo(outboxBefore);
    assertThat(deadLetter(scenario).get("replay_status")).isEqualTo("NEW");
  }

  @Test
  @DisplayName("普通失败任务不能伪用外部动作核实授权")
  void shouldRejectExternalOutcomeResolution_whenTaskOutcomeIsKnown() {
    final String updateJobTaskSql = """
        update batch.job_task set task_status = 'FAILED', error_code = 'VALIDATION_ERROR', error_message =\s\
        'known validation failure', finished_at = now(), version = version + 1 where tenant_id = ? and id = ?\
        """;
    final String updateJobPartitionSql = """
        update batch.job_partition set partition_status = 'FAILED', version = version + 1 where tenant_id = ?\s\
        and id = ?\
        """;

    Scenario scenario = launch("FIXED", 1);
    jdbc.update(
        updateJobTaskSql,
        scenario.tenant(),
        scenario.taskId());
    jdbc.update(
        updateJobPartitionSql,
        scenario.tenant(),
        scenario.partitionId());
    Long deadLetterId = seedDueDeadLetter(scenario);
    long outboxBefore = outboxCount(scenario);

    assertThatThrownBy(() -> retries.replayDeadLetter(
            DeadLetterReplayInput.builder()
                .tenantId(scenario.tenant())
                .deadLetterTaskId(deadLetterId)
                .operatorId("operator-1")
                .reason("verified no external action")
                .idempotencyKey("misapplied-resolution")
                .externalActionVerifiedNotExecuted(true)
                .externalOutcomeEvidenceReference("incident://ops/INC-2049")
                .build()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.dead_letter.external_outcome_resolution_requires_compensation");

    JobTaskEntity task = tasks.selectById(scenario.tenant(), scenario.taskId());
    assertThat(task.getTaskStatus()).isEqualTo("FAILED");
    assertThat(task.getExternalOutcomeResolutionVersion()).isNull();
    assertThat(outboxCount(scenario)).isEqualTo(outboxBefore);
  }

  @Test
  @DisplayName("直接死信服务拒绝绕过审批执行外部结果核验")
  void shouldRejectDirectExternalOutcomeResolutionBeforeMutatingSource() {
    final String updateJobTaskSql = """
        update batch.job_task set task_status = 'FAILED', error_code = ?, error_message =\s\
        'external action was not executed', finished_at = now(), version = version + 1 where tenant_id = ?\s\
        and id = ?\
        """;
    final String updateJobPartitionSql = """
        update batch.job_partition set partition_status = 'FAILED', version = version + 1 where tenant_id = ?\s\
        and id = ?\
        """;
    final String updateDeadLetterTaskSql = """
        update batch.dead_letter_task set payload_ref = 'other-instance:999' where tenant_id = ? and id = ?\
        """;

    Scenario scenario = launch("FIXED", 1);
    jdbc.update(
        updateJobTaskSql,
        TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN,
        scenario.tenant(),
        scenario.taskId());
    jdbc.update(
        updateJobPartitionSql,
        scenario.tenant(),
        scenario.partitionId());
    Long deadLetterId = seedDueDeadLetter(scenario);
    jdbc.update(
        updateDeadLetterTaskSql,
        scenario.tenant(),
        deadLetterId);
    long outboxBefore = outboxCount(scenario);

    assertThatThrownBy(() -> retries.replayDeadLetter(
            DeadLetterReplayInput.builder()
                .tenantId(scenario.tenant())
                .deadLetterTaskId(deadLetterId)
                .operatorId("operator-1")
                .reason("verified external action was not executed")
                .idempotencyKey("mismatched-source")
                .externalActionVerifiedNotExecuted(true)
                .externalOutcomeEvidenceReference("incident://ops/INC-2050")
                .build()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.dead_letter.external_outcome_resolution_requires_compensation");

    JobTaskEntity task = tasks.selectById(scenario.tenant(), scenario.taskId());
    assertThat(task.getTaskStatus()).isEqualTo("FAILED");
    assertThat(task.getExternalOutcomeResolutionVersion()).isNull();
    assertThat(outboxCount(scenario)).isEqualTo(outboxBefore);
  }

  @Test
  @DisplayName("外部结果核验不能通过死信重排队直接复用原分区")
  void shouldRequireApprovedCompensation_whenResolvingUnknownExternalOutcome() {
    final String updateJobTaskSql = """
        update batch.job_task set task_seq = 2, task_status = 'FAILED', error_code = ?, error_message =\s\
        'external action was not executed', finished_at = now(), version = version + 1 where tenant_id = ?\s\
        and id = ?\
        """;
    final String insertJobTaskSql = """
        insert into batch.job_task (tenant_id, job_instance_id, job_partition_id, task_type,
          task_seq, task_status, error_code, error_message, finished_at, job_instance_biz_date)
        select tenant_id, job_instance_id, job_partition_id, task_type,
          1, 'FAILED', 'VALIDATION_ERROR', 'known earlier failure', now(), job_instance_biz_date
        from batch.job_task where tenant_id = ? and id = ?
        returning id
        """;
    final String updateJobPartitionSql = """
        update batch.job_partition set partition_status = 'FAILED', version = version + 1 where tenant_id = ?\s\
        and id = ?\
        """;
    final String updateDeadLetterTaskSql = """
        update batch.dead_letter_task set max_replay_count = 0, next_replay_at = null where tenant_id = ?\s\
        and id = ?\
        """;

    Scenario scenario = launch("FIXED", 1);
    jdbc.update(
        updateJobTaskSql,
        TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN,
        scenario.tenant(),
        scenario.taskId());
    Long earlierKnownFailureId = jdbc.queryForObject(insertJobTaskSql, Long.class, scenario.tenant(), scenario.taskId());
    jdbc.update(
        updateJobPartitionSql,
        scenario.tenant(),
        scenario.partitionId());
    Long deadLetterId = seedDueDeadLetter(scenario);
    jdbc.update(
        updateDeadLetterTaskSql,
        scenario.tenant(),
        deadLetterId);
    long outboxBefore = outboxCount(scenario);

    assertThatThrownBy(() -> retries.replayDeadLetter(
            DeadLetterReplayInput.builder()
                .tenantId(scenario.tenant())
                .deadLetterTaskId(deadLetterId)
                .operatorId("operator-1")
                .reason("external system audit confirmed no action")
                .idempotencyKey("verified-retry-multiple-failures")
                .externalActionVerifiedNotExecuted(true)
                .externalOutcomeEvidenceReference("incident://ops/INC-2051")
                .build()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.dead_letter.external_outcome_resolution_requires_compensation");

    JobTaskEntity unknownTask = tasks.selectById(scenario.tenant(), scenario.taskId());
    JobTaskEntity knownFailure = tasks.selectById(scenario.tenant(), earlierKnownFailureId);
    assertThat(unknownTask.getTaskStatus()).isEqualTo("FAILED");
    assertThat(unknownTask.getExternalOutcomeResolutionVersion()).isNull();
    assertThat(knownFailure.getTaskStatus()).isEqualTo("FAILED");
    assertThat(knownFailure.getExternalOutcomeResolutionVersion()).isNull();
    assertThat(outboxCount(scenario)).isEqualTo(outboxBefore);
    assertThat(deadLetter(scenario).get("replay_status")).isEqualTo("NEW");
  }

  @Test
  @DisplayName("核实授权消费后来源未知结果重新阻止 Job 与业务日重跑")
  void consumedExternalOutcomeResolution_blocksFurtherSourceReruns() {
    final String markSourceTaskUnknownSql = """
        update batch.job_task
        set task_status = 'FAILED', error_code = ?, version = version + 1
        where tenant_id = ? and id = ?
        """;
    final String markResolutionConsumedSql = """
        update batch.job_task
        set external_outcome_resolution_consumed_at = current_timestamp
        where tenant_id = ? and id = ?
        """;

    Scenario scenario = launch("FIXED", 1);
    jdbc.update(
        markSourceTaskUnknownSql,
        TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN,
        scenario.tenant(),
        scenario.taskId());
    JobTaskEntity failedTask = tasks.selectById(scenario.tenant(), scenario.taskId());
    WorkflowRunEntity workflow = workflows.selectById(scenario.tenant(), scenario.workflowId());
    assertThat(workflow.getTriggerRequestId()).isNotNull();
    assertThat(tasks.existsUnknownExternalOutcomeForJobInstance(
        scenario.tenant(), scenario.instanceId())).isTrue();

    assertThat(tasks.markExternalOutcomeResolution(
        scenario.tenant(), scenario.taskId(), failedTask.getVersion()))
        .isEqualTo(1);
    assertThat(tasks.bindExternalOutcomeResolutionToWorkflowRequest(
        scenario.tenant(),
        scenario.taskId(),
        failedTask.getVersion(),
        workflow.getTriggerRequestId()))
        .isEqualTo(1);
    assertThat(tasks.existsUnknownExternalOutcomeForJobInstance(
        scenario.tenant(), scenario.instanceId())).isFalse();

    jdbc.update(markResolutionConsumedSql, scenario.tenant(), scenario.taskId());
    assertThat(tasks.existsUnknownExternalOutcomeForJobInstance(
        scenario.tenant(), scenario.instanceId())).isTrue();
    assertThat(tasks.existsUnknownExternalOutcomeForJobBizDate(
        scenario.tenant(),
        instances.selectById(scenario.tenant(), scenario.instanceId()).getJobCode(),
        BIZ_DATE)).isTrue();
  }

  @Test
  @DisplayName("真实 PostgreSQL 拒绝 JOB 与 BATCH 补偿绕过 UNKNOWN 分区重排队守护")
  void shouldRejectJobAndBatchRerun_whenMatchingTaskHasUnknownExternalOutcome() {
    final String updateJobTaskSql = """
        update batch.job_task set task_status = 'FAILED', error_code = ?, error_message =\s\
        'external effect may have committed', finished_at = now(), version = version + 1 where tenant_id = ?\s\
        and id = ?\
        """;
    final String updateJobPartitionSql = """
        update batch.job_partition set partition_status = 'FAILED', version = version + 1 where tenant_id = ?\s\
        and id = ?\
        """;
    final String updateJobInstanceSql = """
        update batch.job_instance set instance_status = 'FAILED', finished_at = now(), failed_partition_count =\s\
        1, version = version + 1 where tenant_id = ? and id = ?\
        """;
    final String updateWorkflowRunSql = """
        update batch.workflow_run set run_status = 'FAILED', finished_at = now() where tenant_id = ? and id = ?\
        """;
    final String selectJobCodeSql = """
        select job_code from batch.job_instance where tenant_id = ? and id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.trigger_request where tenant_id = ?
    """;
    final String selectCountSql2 = """
        select count(*) from batch.job_instance where tenant_id = ?
    """;

    Scenario scenario = launch("FIXED", 1);
    jdbc.update(
        updateJobTaskSql,
        TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN,
        scenario.tenant(),
        scenario.taskId());
    jdbc.update(
        updateJobPartitionSql,
        scenario.tenant(),
        scenario.partitionId());
    jdbc.update(
        updateJobInstanceSql,
        scenario.tenant(),
        scenario.instanceId());
    jdbc.update(
        updateWorkflowRunSql,
        scenario.tenant(),
        scenario.workflowId());
    String jobCode = jdbc.queryForObject(
        selectJobCodeSql,
        String.class,
        scenario.tenant(),
        scenario.instanceId());
    assertThat(tasks.existsUnknownExternalOutcomeForJobInstance(
            scenario.tenant(), scenario.instanceId()))
        .isTrue();
    assertThat(tasks.existsUnknownExternalOutcomeForJobBizDate(
            scenario.tenant(), jobCode, BIZ_DATE))
        .isTrue();
    assertThat(tasks.existsUnknownExternalOutcomeForJobBizDate(
            scenario.tenant(), jobCode, BIZ_DATE.plusDays(1)))
        .isFalse();
    long outboxBefore = outboxCount(scenario);
    long triggerRequestsBefore = jdbc.queryForObject(
        selectCountSql,
        Long.class,
        scenario.tenant());
    long instancesBefore = jdbc.queryForObject(
        selectCountSql2,
        Long.class,
        scenario.tenant());

    CompensationSubmitCommand jobCommand = CompensationSubmitCommand.builder()
        .tenantId(scenario.tenant())
        .compensationType("JOB")
        .targetId(scenario.instanceId())
        .reason("unknown external result requires reconciliation")
        .build();
    CompensationSubmitCommand batchCommand = CompensationSubmitCommand.builder()
        .tenantId(scenario.tenant())
        .compensationType("BATCH")
        .jobCode(jobCode)
        .bizDate(BIZ_DATE)
        .reason("unknown external result requires reconciliation")
        .build();
    assertThatThrownBy(() -> compensation.submit(jobCommand))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.task.retry_unknown_outcome_requires_resolution");
    assertThatThrownBy(() -> compensation.submit(batchCommand))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.task.retry_unknown_outcome_requires_resolution");

    assertThat(outboxCount(scenario)).isEqualTo(outboxBefore);
    Long selectedTriggerRequestCount =
        jdbc.queryForObject(selectCountSql, Long.class, scenario.tenant());
    assertThat(selectedTriggerRequestCount).isEqualTo(triggerRequestsBefore);
    Long selectedJobInstanceCount =
        jdbc.queryForObject(selectCountSql2, Long.class, scenario.tenant());
    assertThat(selectedJobInstanceCount).isEqualTo(instancesBefore);
  }

  @Test
  @DisplayName("已有到期重试在 Workflow 终结后标记失败，不会永久留 WAITING 或重开任务")
  void shouldCloseDueRetry_whenParentWorkflowTerminates() {
    final String updateWorkflowRunSql = """
        update batch.workflow_run set run_status = 'TERMINATED' where id = ?
    """;

    Scenario scenario = launch("FIXED", 1);
    fail(scenario);
    jdbc.update(
        updateWorkflowRunSql,
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
    final String updateWorkflowRunSql = """
        update batch.workflow_run set run_status = 'PAUSED' where id = ?
    """;
    final String updateWorkflowRunSql2 = """
        update batch.workflow_run set run_status = 'RUNNING' where id = ?
    """;

    Scenario scenario = launch("FIXED", 1);
    fail(scenario);
    makeRetriesDue(scenario);
    jdbc.update(
        updateWorkflowRunSql, scenario.workflowId());
    List<Map<String, Object>> before = executionRows(scenario);
    retries.dispatchDueRetries();
    assertThat(retryStatus(scenario)).isEqualTo("WAITING");
    assertThat(executionRows(scenario)).isEqualTo(before);
    jdbc.update(
        updateWorkflowRunSql2, scenario.workflowId());
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
    TaskDispatchOutboxService target = AopTestUtils.getUltimateTargetObject(dispatch);
    doThrow(new IllegalStateException("SIM_REPLAY_OUTBOX_FAILED"))
        .when(target)
        .writeDispatchEvent(
            org.mockito.ArgumentMatchers.argThat(command -> command != null && (scenario.tenant().equals(command.jobInstance().getTenantId()))));

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
    final String updateDeadLetterTaskSql = """
        update batch.dead_letter_task set max_replay_count = 3, next_replay_at = now() - interval '1 second'\s\
        where tenant_id = ?
    """;

    Scenario scenario = launch("NONE", 0);
    fail(scenario);
    jdbc.update(
        updateDeadLetterTaskSql,
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
    final String updateJobInstanceSql = """
        update batch.job_instance set instance_status = 'FAILED' where id = ?
    """;
    final String selectCountSql = """
        select count(*) from pg_locks where locktype = 'advisory' and not granted
    """;

    Scenario scenario = launch("FIXED", 1);
    fail(scenario);
    makeRetriesDue(scenario);
    long outboxBefore = outboxCount(scenario);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    CompletableFuture<Void> retry = transaction.execute(status -> {
      instances.acquireInstanceAdvisoryLock(scenario.tenant(), scenario.instanceId());
      jdbc.update(
          updateJobInstanceSql,
          scenario.instanceId());
      CompletableFuture<Void> future = CompletableFuture.runAsync(retries::dispatchDueRetries);
      Awaitility.await()
          .atMost(10, TimeUnit.SECONDS)
          .until(() -> jdbc.queryForObject(
                  selectCountSql,
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
    final String selectCountSql = """
        select count(*) from batch.dead_letter_task where tenant_id = ?
    """;

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
    Long selectedDeadLetterTaskCount =
        jdbc.queryForObject(selectCountSql, Long.class, scenario.tenant());
    assertThat(selectedDeadLetterTaskCount).isZero();
    assertThat(captured.getAll()).doesNotContain(warning);

    execution.applyTaskOutcome(failure(scenario));

    assertThat(captured.getAll().split(Pattern.quote(warning), -1)).hasSize(2);
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
    final String updateJobDefinitionSql = """
        update batch.job_definition set retry_policy = ?, retry_max_count = ? where tenant_id = ?\s\
        and job_code = ?
    """;

    String tenant = "retry-" + UUID.randomUUID().toString().substring(0, 12);
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbc, tenant, "IMPORT", "DEFAULT", TriggerType.API, BIZ_DATE);
    jdbc.update(
        updateJobDefinitionSql,
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
    List<JobTaskEntity> found = tasks.selectByQuery(JobTaskQuery.ofJobInstance(tenant, instanceId));
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
    final String updateRetryScheduleSql = """
        update batch.retry_schedule set next_retry_at = now() - interval '1 second' where tenant_id = ?
    """;

    jdbc.update(
        updateRetryScheduleSql,
        scenario.tenant());
  }

  private String retryStatus(Scenario scenario) {
    final String selectRetryStatusSql = """
        select retry_status from batch.retry_schedule where tenant_id = ?
    """;

    return jdbc.queryForObject(
        selectRetryStatusSql,
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
    final String selectCountSql = """
        select count(*) from batch.outbox_event where tenant_id = ?
    """;

    return jdbc.queryForObject(
        selectCountSql,
        Long.class,
        scenario.tenant());
  }

  private Map<String, Object> deadLetter(Scenario scenario) {
    final String selectStatementSql = """
        select * from batch.dead_letter_task where tenant_id = ?
    """;

    return jdbc.queryForMap(
        selectStatementSql, scenario.tenant());
  }

  private List<Map<String, Object>> executionRows(Scenario scenario) {
    final String selectStatementSql = """
        select 'task' as kind, id, task_status as status, version from batch.job_task where tenant_id = ?
        union all select 'partition', id, partition_status, version from batch.job_partition where tenant_id = ?
        union all select 'step', id, step_status, version from batch.job_step_instance where tenant_id = ?
        order by kind, id
        """;

    return jdbc.queryForList(selectStatementSql, scenario.tenant(), scenario.tenant(), scenario.tenant());
  }

  private Long seedDueDeadLetter(Scenario scenario) {
    final String insertDeadLetterTaskSql = """
        insert into batch.dead_letter_task (tenant_id, source_type, source_id, dead_letter_reason,
          payload_ref, replay_status, replay_count, max_replay_count, next_replay_at, error_class)
        values (?, 'JOB_PARTITION', ?, 'test',
          (select instance_no from batch.job_instance where tenant_id = ? and id = ?) || ':' || ?,
          'NEW', 0, 3, now() - interval '1 second', 'SYSTEM')
        returning id
        """;

    return jdbc.queryForObject(insertDeadLetterTaskSql,
        Long.class,
        scenario.tenant(),
        scenario.partitionId(),
        scenario.tenant(),
        scenario.instanceId(),
        scenario.taskId());
  }

  private record Scenario(
      String tenant,
      Long instanceId,
      Long taskId,
      Long partitionId,
      Long workflowId,
      String worker) {}
}
