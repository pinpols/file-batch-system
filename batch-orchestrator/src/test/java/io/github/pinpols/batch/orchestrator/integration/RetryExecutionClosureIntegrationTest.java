package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import io.github.pinpols.batch.orchestrator.application.service.governance.CompensationService;
import io.github.pinpols.batch.orchestrator.application.service.governance.DeadLetterReplayInput;
import io.github.pinpols.batch.orchestrator.application.service.governance.FileGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskExecutionService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.config.RetryGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.command.CompensationSubmitCommand;
import io.github.pinpols.batch.orchestrator.domain.command.FileGovernanceCommand;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.DeadLetterTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.param.AssignWorkerParam;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.domain.query.RetryScheduleQuery;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.orchestrator.mapper.CompensationCommandMapper;
import io.github.pinpols.batch.orchestrator.mapper.DeadLetterTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.RetryScheduleMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
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
  private RetryGovernanceProperties retryProperties;

  @Autowired
  private RetryScheduleMapper retrySchedules;

  @Autowired
  private CompensationService compensation;

  @Autowired
  private FileGovernanceService fileGovernanceService;

  @Autowired
  private JobTaskMapper tasks;

  @Autowired
  private JobPartitionMapper partitions;

  @Autowired
  private JobInstanceMapper instances;

  @Autowired
  private WorkflowRunMapper workflows;

  @Autowired
  private DeadLetterTaskMapper deadLetters;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @MockitoSpyBean
  private TaskDispatchOutboxService dispatch;

  @MockitoSpyBean
  private CompensationCommandMapper compensationCommands;

  @ParameterizedTest
  @ValueSource(strings = {"SQL_NULL", "null", "[]", "\"text\"", "{}", "{\"dryRun\": false}"})
  @DisplayName("演练任务载荷异常或演练标记不一致时不得重试派发")
  void shouldRejectRetryWhenDryRunPayloadUnsafe(String payload) {
    assertRetryRejectsUnsafePayload(true, "SQL_NULL".equals(payload) ? null : payload);
  }

  @Test
  @DisplayName("正式任务载荷误标为演练时不得重试派发")
  void shouldRejectRetryWhenFormalPayloadClaimsDryRun() {
    assertRetryRejectsUnsafePayload(false, "{\"dryRun\": true}");
  }

  private void assertRetryRejectsUnsafePayload(boolean dryRun, String storedPayload) {
    final String updateInstanceSql =
        "update batch.job_instance set dry_run = ? where tenant_id = ? and id = ?";
    final String updatePartitionSql =
        "update batch.job_partition set dry_run = ?, partition_status = 'FAILED' where tenant_id = ? and id = ?";
    final String updateTaskSql =
        "update batch.job_task set dry_run = ?, task_status = 'FAILED', task_payload = ?::jsonb where tenant_id = ? and id = ?";
    final String selectPayloadSql =
        "select task_payload::text from batch.job_task where tenant_id = ? and id = ?";
    Scenario scenario = launch("FIXED", 1);
    jdbc.update(updateInstanceSql, dryRun, scenario.tenant(), scenario.instanceId());
    jdbc.update(updatePartitionSql, dryRun, scenario.tenant(), scenario.partitionId());
    jdbc.update(updateTaskSql, dryRun, storedPayload, scenario.tenant(), scenario.taskId());
    List<Map<String, Object>> before = executionRows(scenario);
    long outboxBefore = outboxCount(scenario);

    assertThatThrownBy(() -> retries.retryPartition(
            scenario.tenant(), scenario.partitionId(), "invalid-payload-" + scenario.tenant()))
        .isInstanceOf(BizException.class)
        .satisfies(error -> {
          BizException conflict = (BizException) error;
          assertThat(conflict.getCode()).isEqualTo(ResultCode.STATE_CONFLICT);
          assertThat(conflict.getMessageKey()).isEqualTo("error.task.retry_payload_unsafe");
        });

    assertThat(executionRows(scenario)).isEqualTo(before);
    assertThat(outboxCount(scenario)).isEqualTo(outboxBefore);
    assertThat(jdbc.queryForObject(
            selectPayloadSql, String.class, scenario.tenant(), scenario.taskId()))
        .isEqualTo(storedPayload);
    assertThat(tasks.selectById(scenario.tenant(), scenario.taskId()).getDryRun())
        .isEqualTo(dryRun);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("父作业与任务演练标记不一致时不得重试派发")
  void shouldRejectRetryWhenInstanceAndTaskDryRunDiffer(boolean instanceDryRun) {
    final String updateInstanceSql =
        "update batch.job_instance set dry_run = ? where tenant_id = ? and id = ?";
    final String updatePartitionSql =
        "update batch.job_partition set dry_run = ?, partition_status = 'FAILED' where tenant_id = ? and id = ?";
    final String updateTaskSql =
        "update batch.job_task set dry_run = ?, task_status = 'FAILED', task_payload = jsonb_build_object('dryRun', ?::boolean) where tenant_id = ? and id = ?";
    Scenario scenario = launch("FIXED", 1);
    boolean taskDryRun = !instanceDryRun;
    jdbc.update(updateInstanceSql, instanceDryRun, scenario.tenant(), scenario.instanceId());
    jdbc.update(updatePartitionSql, instanceDryRun, scenario.tenant(), scenario.partitionId());
    jdbc.update(updateTaskSql, taskDryRun, taskDryRun, scenario.tenant(), scenario.taskId());
    List<Map<String, Object>> before = executionRows(scenario);
    long outboxBefore = outboxCount(scenario);

    assertThatThrownBy(() -> retries.retryPartition(
            scenario.tenant(), scenario.partitionId(), "conflicting-dryrun-" + scenario.tenant()))
        .isInstanceOf(BizException.class)
        .satisfies(error -> {
          BizException conflict = (BizException) error;
          assertThat(conflict.getCode()).isEqualTo(ResultCode.STATE_CONFLICT);
          assertThat(conflict.getMessageKey()).isEqualTo("error.task.retry_payload_unsafe");
        });
    assertThat(executionRows(scenario)).isEqualTo(before);
    assertThat(outboxCount(scenario)).isEqualTo(outboxBefore);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("合法载荷重试保留演练标记和业务参数")
  void shouldPreserveDryRunAndBusinessPayloadOnRetry(boolean dryRun) {
    final String updateInstanceSql =
        "update batch.job_instance set dry_run = ? where tenant_id = ? and id = ?";
    final String updatePartitionSql =
        "update batch.job_partition set dry_run = ?, partition_status = 'FAILED' where tenant_id = ? and id = ?";
    final String updateTaskSql =
        "update batch.job_task set dry_run = ?, task_status = 'FAILED', task_payload = jsonb_build_object('dryRun', ?::boolean, 'businessKey', 'retained') where tenant_id = ? and id = ?";
    final String selectTaskSql =
        "select task_status, task_payload ->> 'dryRun' as dry_run_payload, task_payload ->> 'run_mode' as run_mode, task_payload ->> 'businessKey' as business_key from batch.job_task where tenant_id = ? and id = ?";
    Scenario scenario = launch("FIXED", 1);
    jdbc.update(updateInstanceSql, dryRun, scenario.tenant(), scenario.instanceId());
    jdbc.update(updatePartitionSql, dryRun, scenario.tenant(), scenario.partitionId());
    jdbc.update(updateTaskSql, dryRun, dryRun, scenario.tenant(), scenario.taskId());
    long outboxBefore = outboxCount(scenario);

    retries.retryPartition(
        scenario.tenant(), scenario.partitionId(), "valid-payload-" + scenario.tenant());

    Map<String, Object> persisted =
        jdbc.queryForMap(selectTaskSql, scenario.tenant(), scenario.taskId());
    assertThat(persisted)
        .containsEntry("task_status", "READY")
        .containsEntry("dry_run_payload", Boolean.toString(dryRun))
        .containsEntry("run_mode", "RETRY")
        .containsEntry("business_key", "retained");
    assertThat(outboxCount(scenario)).isEqualTo(outboxBefore + 1);
  }

  @Test
  @DisplayName("多个 DISPATCH 任务时只重派目标文件关联的任务")
  void shouldRedispatchTaskAssociatedWithTargetFile() {
    final String insertFileRecordSql1 = """
        insert into batch.file_record
          (tenant_id, file_category, file_name, file_format_type, storage_type, storage_path, source_type, file_status)
        values (?, 'OUTPUT', 'other-file', 'BINARY', 'LOCAL', ?, 'SYSTEM', 'RECEIVED') returning id
        """;
    final String insertFileRecordSql2 = """
        insert into batch.file_record
          (tenant_id, file_category, file_name, file_format_type, storage_type, storage_path, source_type, file_status)
        values (?, 'OUTPUT', 'target-file', 'BINARY', 'LOCAL', ?, 'SYSTEM', 'RECEIVED') returning id
        """;
    final String updateJobTaskSql3 =
        "update batch.job_task set task_type = 'DISPATCH', task_status = 'FAILED', task_payload = jsonb_build_object('fileId', ?, 'channelCode', 'CH1') where tenant_id = ? and id = ?";
    final String updateJobStepInstanceSql4 =
        "update batch.job_step_instance set related_file_id = ? where tenant_id = ? and job_task_id = ?";
    final String insertJobPartitionSql5 = """
        insert into batch.job_partition
          (tenant_id, job_instance_id, job_instance_biz_date, partition_no, partition_status, business_key, idempotency_key)
        select tenant_id, id, biz_date, 2, 'FAILED', ?, ? from batch.job_instance
        where tenant_id = ? and id = ? returning id
        """;
    final String insertJobTaskSql6 = """
        insert into batch.job_task
          (tenant_id, job_instance_id, job_instance_biz_date, job_partition_id, task_type, task_seq, task_status, task_payload)
        select tenant_id, id, biz_date, ?, 'DISPATCH', 2, 'FAILED', jsonb_build_object('fileId', ?, 'channelCode', 'CH1')
        from batch.job_instance where tenant_id = ? and id = ? returning id
        """;
    final String insertJobStepInstanceSql7 = """
        insert into batch.job_step_instance
          (tenant_id, job_instance_id, job_partition_id, job_task_id, step_code, step_type, step_status, related_file_id)
        values (?, ?, ?, ?, 'DISPATCH_2', 'DISPATCH', 'FAILED', ?)
        """;
    final String insertPipelineInstanceSql8 = """
        insert into batch.pipeline_instance
          (tenant_id, system_pipeline_code, system_pipeline_version, job_code, pipeline_type,
           file_id, related_job_instance_id, run_status)
        select ?, 'DISPATCH', 1, job_code, 'DISPATCH', ?, id, 'FAILED'
          from batch.job_instance where tenant_id = ? and id = ? returning id
        """;
    final String insertFileDispatchRecordSql9 = """
        insert into batch.file_dispatch_record
          (tenant_id, file_id, pipeline_instance_id, channel_code, dispatch_status, receipt_status)
        values (?, ?, ?, 'CH1', 'FAILED', 'FAILED')
        """;
    final String updateJobStepInstanceSql10 =
        "update batch.job_step_instance set related_file_id = null where tenant_id = ? and job_task_id = ?";
    final String updateJobStepInstanceSql11 =
        "update batch.job_step_instance set related_file_id = ? where tenant_id = ? and job_task_id = ?";
    final String updateJobStepInstanceSql12 =
        "update batch.job_step_instance set related_file_id = ? where tenant_id = ? and job_task_id = ?";
    final String updateJobTaskSql13 =
        "update batch.job_task set task_payload = jsonb_build_object('fileId', ?, 'channelCode', 'CH1') where tenant_id = ? and id = ?";
    final String updateJobTaskSql14 =
        "update batch.job_task set task_payload = jsonb_build_object('fileId', ?, 'channelCode', 'CH2') where tenant_id = ? and id = ?";
    final String updateJobTaskSql15 =
        "update batch.job_task set task_payload = jsonb_build_object('fileId', ?) where tenant_id = ? and id = ?";
    final String updateJobTaskSql16 =
        "update batch.job_task set task_payload = jsonb_build_object('fileId', ?, 'channelCode', 'CH2') where tenant_id = ? and id = ?";
    final String selectOutboxEventSql17 =
        "select count(*) from batch.outbox_event where tenant_id = ? and aggregate_id = ? and event_key like ?";
    Scenario scenario = launch("NONE", 0);
    Long otherFile = jdbc.queryForObject(
        insertFileRecordSql1, Long.class, scenario.tenant(), "other-" + scenario.tenant());
    Long targetFile = jdbc.queryForObject(
        insertFileRecordSql2, Long.class, scenario.tenant(), "target-" + scenario.tenant());
    jdbc.update(updateJobTaskSql3, otherFile, scenario.tenant(), scenario.taskId());
    jdbc.update(updateJobStepInstanceSql4, otherFile, scenario.tenant(), scenario.taskId());
    Long partitionId = jdbc.queryForObject(
        insertJobPartitionSql5,
        Long.class,
        "business-" + scenario.tenant(),
        "identity-" + scenario.tenant(),
        scenario.tenant(),
        scenario.instanceId());
    Long targetTask = jdbc.queryForObject(
        insertJobTaskSql6,
        Long.class,
        partitionId,
        targetFile,
        scenario.tenant(),
        scenario.instanceId());
    jdbc.update(
        insertJobStepInstanceSql7,
        scenario.tenant(),
        scenario.instanceId(),
        partitionId,
        targetTask,
        targetFile);
    Long pipelineId = jdbc.queryForObject(
        insertPipelineInstanceSql8,
        Long.class,
        scenario.tenant(),
        targetFile,
        scenario.tenant(),
        scenario.instanceId());
    jdbc.update(insertFileDispatchRecordSql9, scenario.tenant(), targetFile, pipelineId);
    var command = FileGovernanceCommand.builder()
        .tenantId(scenario.tenant())
        .fileId(targetFile)
        .channelCode("CH1")
        .operatorId("integration")
        .build();

    long beforeOutbox = outboxCount(scenario);
    jdbc.update(updateJobStepInstanceSql10, scenario.tenant(), targetTask);
    assertThatThrownBy(() -> fileGovernanceService.redispatchFile(command))
        .isInstanceOf(BizException.class)
        .hasMessage("error.dispatch.redispatch_task_ambiguous");
    jdbc.update(updateJobStepInstanceSql11, targetFile, scenario.tenant(), targetTask);
    jdbc.update(updateJobStepInstanceSql12, targetFile, scenario.tenant(), scenario.taskId());
    jdbc.update(updateJobTaskSql13, targetFile, scenario.tenant(), scenario.taskId());
    assertThatThrownBy(() -> fileGovernanceService.redispatchFile(command))
        .isInstanceOf(BizException.class)
        .hasMessage("error.dispatch.redispatch_task_ambiguous");
    assertThat(outboxCount(scenario)).isEqualTo(beforeOutbox);
    jdbc.update(updateJobTaskSql14, otherFile, scenario.tenant(), scenario.taskId());
    assertThatThrownBy(() -> fileGovernanceService.redispatchFile(command))
        .isInstanceOf(BizException.class)
        .hasMessage("error.dispatch.redispatch_task_ambiguous");
    jdbc.update(updateJobTaskSql15, targetFile, scenario.tenant(), scenario.taskId());
    assertThatThrownBy(() -> fileGovernanceService.redispatchFile(command))
        .isInstanceOf(BizException.class)
        .hasMessage("error.dispatch.redispatch_task_ambiguous");
    assertThat(outboxCount(scenario)).isEqualTo(beforeOutbox);
    jdbc.update(updateJobTaskSql16, targetFile, scenario.tenant(), scenario.taskId());

    assertThat(fileGovernanceService.redispatchFile(command)).isEqualTo("REDISPATCH_ACCEPTED");
    assertThat(tasks.selectById(scenario.tenant(), scenario.taskId()).getTaskStatus())
        .isEqualTo("FAILED");
    assertThat(tasks.selectById(scenario.tenant(), targetTask).getTaskStatus()).isEqualTo("READY");
    assertThat(jdbc.queryForObject(
            selectOutboxEventSql17,
            Long.class,
            scenario.tenant(),
            targetTask,
            scenario.tenant() + ":file-redispatch:%"))
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("一次重派失败后再次重派必须生成新的派发事件")
  void shouldCreateNewEventForLaterFileRedispatch() {
    final String updateJobTaskSql18 =
        "update batch.job_task set task_type = 'DISPATCH', task_status = 'FAILED' where tenant_id = ? and id = ?";
    final String insertFileRecordSql19 = """
        insert into batch.file_record
          (tenant_id, file_category, file_name, file_format_type, storage_type, storage_path,
           source_type, file_status)
        values (?, 'OUTPUT', ?, 'BINARY', 'LOCAL', ?, 'SYSTEM', 'RECEIVED') returning id
        """;
    final String updateJobStepInstanceSql20 =
        "update batch.job_step_instance set related_file_id = ? where tenant_id = ? and job_task_id = ?";
    final String updateJobTaskSql21 =
        "update batch.job_task set task_payload = jsonb_build_object('fileId', ?, 'channelCode', 'CH1') where tenant_id = ? and id = ?";
    final String insertPipelineInstanceSql22 = """
        insert into batch.pipeline_instance
          (tenant_id, system_pipeline_code, system_pipeline_version, job_code, pipeline_type,
           file_id, related_job_instance_id, run_status)
        select ?, 'DISPATCH', 1, job_code, 'DISPATCH', ?, id, 'FAILED'
          from batch.job_instance where tenant_id = ? and id = ? returning id
        """;
    final String insertFileDispatchRecordSql23 = """
        insert into batch.file_dispatch_record
          (tenant_id, file_id, pipeline_instance_id, channel_code, dispatch_status, receipt_status)
        values (?, ?, ?, 'CH1', 'FAILED', 'FAILED') returning id
        """;
    final String updateOutboxEventSql24 =
        "update batch.outbox_event set publish_status = 'PUBLISHED' where tenant_id = ? and event_key like ?";
    final String updateJobTaskSql25 =
        "update batch.job_task set task_status = 'FAILED', version = version + 1 where tenant_id = ? and id = ?";
    final String updateJobPartitionSql26 =
        "update batch.job_partition set partition_status = 'FAILED', version = version + 1 where tenant_id = ? and id = ?";
    final String updateFileDispatchRecordSql27 =
        "update batch.file_dispatch_record set dispatch_status = 'FAILED' where id = ?";
    final String selectOutboxEventSql28 =
        "select count(*) from batch.outbox_event where tenant_id = ? and event_key like ?";
    Scenario scenario = launch("NONE", 0);
    jdbc.update(updateJobTaskSql18, scenario.tenant(), scenario.taskId());
    Long fileId = jdbc.queryForObject(
        insertFileRecordSql19,
        Long.class,
        scenario.tenant(),
        "file-" + scenario.tenant(),
        "path-" + scenario.tenant());
    jdbc.update(updateJobStepInstanceSql20, fileId, scenario.tenant(), scenario.taskId());
    jdbc.update(updateJobTaskSql21, fileId, scenario.tenant(), scenario.taskId());
    Long pipelineId = jdbc.queryForObject(
        insertPipelineInstanceSql22,
        Long.class,
        scenario.tenant(),
        fileId,
        scenario.tenant(),
        scenario.instanceId());
    Long dispatchId = jdbc.queryForObject(
        insertFileDispatchRecordSql23, Long.class, scenario.tenant(), fileId, pipelineId);
    var command = FileGovernanceCommand.builder()
        .tenantId(scenario.tenant())
        .fileId(fileId)
        .operatorId("integration")
        .build();
    assertThat(fileGovernanceService.redispatchFile(command)).isEqualTo("REDISPATCH_ACCEPTED");
    String eventPrefix = scenario.tenant() + ":file-redispatch:%";
    assertThat(jdbc.update(updateOutboxEventSql24, scenario.tenant(), eventPrefix))
        .isEqualTo(1);
    assertThatThrownBy(() -> fileGovernanceService.redispatchFile(command))
        .isInstanceOf(BizException.class)
        .satisfies(error -> assertThat(((BizException) error).getMessageKey())
            .isEqualTo("error.dispatch.redispatch_requires_failed_task"));
    jdbc.update(updateJobTaskSql25, scenario.tenant(), scenario.taskId());
    jdbc.update(updateJobPartitionSql26, scenario.tenant(), scenario.partitionId());
    jdbc.update(updateFileDispatchRecordSql27, dispatchId);

    assertThat(fileGovernanceService.redispatchFile(command)).isEqualTo("REDISPATCH_ACCEPTED");
    assertThat(
            jdbc.queryForObject(selectOutboxEventSql28, Long.class, scenario.tenant(), eventPrefix))
        .isEqualTo(2L);
  }

  @Test
  @DisplayName("作业暂停时文件重派拒绝并回滚投递记录、出箱和成功审计")
  void shouldRollbackFileRedispatchWhenParentIsPaused() {
    assertFileRedispatchRollsBack("paused");
  }

  @Test
  @DisplayName("非失败状态任务不能文件重派，不改变分区和投递记录")
  void shouldRollbackFileRedispatchWhenTaskCannotReset() {
    assertFileRedispatchRollsBack("success");
  }

  @Test
  @DisplayName("外部投递结果未知时文件重派必须拒绝")
  void shouldRejectFileRedispatchWhenExternalOutcomeIsUnknown() {
    assertFileRedispatchRollsBack("unknown");
  }

  @ParameterizedTest
  @ValueSource(strings = {"CREATED", "SENT", "ACKED", "UNKNOWN"})
  @DisplayName("未确认失败或结果未知的投递记录不能重派")
  void shouldRejectFileRedispatchWhenRecordIsNotKnownFailed(String recordState) {
    assertFileRedispatchRollsBack("record-" + recordState);
  }

  @Test
  @DisplayName("单个任务缺少文件关联时拒绝重派且不改变数据库")
  void shouldRejectFileRedispatchWhenSingleTaskHasNoFileAssociation() {
    assertFileRedispatchRollsBack("no-association");
  }

  @ParameterizedTest
  @ValueSource(strings = {"file", "channel", "bundle-file", "bundle-channel"})
  @DisplayName("任务载荷文件或渠道与请求不一致时不得重派")
  void shouldRejectRedispatchWhenPayloadTargetDiffers(String target) {
    assertFileRedispatchRollsBack("payload-" + target);
  }

  private void assertFileRedispatchRollsBack(String reason) {
    final String updateJobInstanceSql29 =
        "update batch.job_instance set instance_status = 'PAUSED' where tenant_id = ? and id = ?";
    final String updateJobTaskSql30 =
        "update batch.job_task set task_status = 'FAILED' where tenant_id = ? and id = ?";
    final String updateJobTaskSql31 =
        "update batch.job_task set task_status = 'SUCCESS' where tenant_id = ? and id = ?";
    final String updateJobTaskSql32 =
        "update batch.job_task set task_status = 'FAILED', error_code = 'EXTERNAL_OUTCOME_UNKNOWN' where tenant_id = ? and id = ?";
    final String updateJobTaskSql33 =
        "update batch.job_task set task_status = 'FAILED' where tenant_id = ? and id = ?";
    final String updateJobTaskSql34 =
        "update batch.job_task set task_type = 'DISPATCH' where tenant_id = ? and id = ?";
    final String insertFileRecordSql35 = """
        insert into batch.file_record
          (tenant_id, file_category, file_name, file_format_type, storage_type, storage_path,
           source_type, file_status)
        values (?, 'OUTPUT', ?, 'BINARY', 'LOCAL', ?, 'SYSTEM', 'RECEIVED') returning id
        """;
    final String updateJobStepInstanceSql36 =
        "update batch.job_step_instance set related_file_id = ? where tenant_id = ? and job_task_id = ?";
    final String updateJobTaskSql37 =
        "update batch.job_task set task_payload = jsonb_build_object('fileId', ?) where tenant_id = ? and id = ?";
    final String updateJobTaskSql38 =
        "update batch.job_task set task_payload = jsonb_build_object('fileId', ?, 'channelCode', 'CH1') where tenant_id = ? and id = ?";
    final String updateJobTaskSql39 =
        "update batch.job_task set task_payload = jsonb_build_object('fileId', ?::bigint, 'channelCode', ?::text, 'bundleSourceFileId', ?::bigint, 'bundleTargetRef', ?::text) where tenant_id = ? and id = ?";
    final String insertPipelineInstanceSql40 = """
        insert into batch.pipeline_instance
          (tenant_id, system_pipeline_code, system_pipeline_version, job_code, pipeline_type,
           file_id, related_job_instance_id, run_status)
        select ?, 'DISPATCH', 1, job_code, 'DISPATCH', ?, id, 'FAILED'
          from batch.job_instance where tenant_id = ? and id = ? returning id
        """;
    final String insertFileDispatchRecordSql41 = """
        insert into batch.file_dispatch_record
          (tenant_id, file_id, pipeline_instance_id, channel_code, dispatch_status, receipt_status)
        values (?, ?, ?, 'CH1', 'FAILED', 'FAILED') returning id
        """;
    final String updateFileDispatchRecordSql42 =
        "update batch.file_dispatch_record set error_code = 'EXTERNAL_OUTCOME_UNKNOWN' where id = ?";
    final String updateFileDispatchRecordSql43 =
        "update batch.file_dispatch_record set dispatch_status = ? where id = ?";
    final String selectFileDispatchRecordSql44 =
        "select dispatch_status from batch.file_dispatch_record where id = ?";
    final String selectFileDispatchRecordSql45 =
        "select receipt_status from batch.file_dispatch_record where id = ?";
    final String selectFileAuditLogSql46 =
        "select count(*) from batch.file_audit_log where tenant_id = ? and file_id = ? and operation_type = 'REDISPATCH'";
    Scenario scenario = launch("NONE", 0);
    if ("paused".equals(reason)) {
      jdbc.update(updateJobInstanceSql29, scenario.tenant(), scenario.instanceId());
      jdbc.update(updateJobTaskSql30, scenario.tenant(), scenario.taskId());
    } else if ("success".equals(reason)) {
      jdbc.update(updateJobTaskSql31, scenario.tenant(), scenario.taskId());
    } else if ("unknown".equals(reason)) {
      jdbc.update(updateJobTaskSql32, scenario.tenant(), scenario.taskId());
    } else {
      jdbc.update(updateJobTaskSql33, scenario.tenant(), scenario.taskId());
    }
    jdbc.update(updateJobTaskSql34, scenario.tenant(), scenario.taskId());
    Long fileId = jdbc.queryForObject(
        insertFileRecordSql35,
        Long.class,
        scenario.tenant(),
        "file-" + scenario.tenant(),
        "path-" + scenario.tenant());
    if (!"no-association".equals(reason)) {
      jdbc.update(updateJobStepInstanceSql36, fileId, scenario.tenant(), scenario.taskId());
    } else {
      jdbc.update(updateJobTaskSql37, fileId + 1, scenario.tenant(), scenario.taskId());
    }
    if (!"no-association".equals(reason)) {
      jdbc.update(updateJobTaskSql38, fileId, scenario.tenant(), scenario.taskId());
    }
    if (reason.startsWith("payload-")) {
      String target = reason.substring("payload-".length());
      Long ordinaryFile = "file".equals(target) ? fileId + 1 : fileId;
      String ordinaryChannel = "channel".equals(target) ? "CH2" : "CH1";
      Long bundleFile = "bundle-file".equals(target) ? fileId + 1 : null;
      String bundleChannel = "bundle-channel".equals(target) ? "CH2" : null;
      jdbc.update(
          updateJobTaskSql39,
          ordinaryFile,
          ordinaryChannel,
          bundleFile,
          bundleChannel,
          scenario.tenant(),
          scenario.taskId());
    }
    Long pipelineId = jdbc.queryForObject(
        insertPipelineInstanceSql40,
        Long.class,
        scenario.tenant(),
        fileId,
        scenario.tenant(),
        scenario.instanceId());
    Long dispatchId = jdbc.queryForObject(
        insertFileDispatchRecordSql41, Long.class, scenario.tenant(), fileId, pipelineId);
    String expectedRecordStatus = "FAILED";
    if (reason.startsWith("record-")) {
      String recordState = reason.substring("record-".length());
      if ("UNKNOWN".equals(recordState)) {
        jdbc.update(updateFileDispatchRecordSql42, dispatchId);
      } else {
        expectedRecordStatus = recordState;
        jdbc.update(updateFileDispatchRecordSql43, recordState, dispatchId);
      }
    }
    long beforeOutbox = outboxCount(scenario);
    var beforePartition = partition(scenario);
    var command = FileGovernanceCommand.builder()
        .tenantId(scenario.tenant())
        .fileId(fileId)
        .channelCode("CH1")
        .operatorId("integration")
        .build();

    assertThatThrownBy(() -> fileGovernanceService.redispatchFile(command))
        .isInstanceOf(BizException.class)
        .satisfies(error -> {
          BizException conflict = (BizException) error;
          assertThat(conflict.getCode()).isEqualTo(ResultCode.STATE_CONFLICT);
          if ("unknown".equals(reason)) {
            assertThat(conflict.getMessageKey())
                .isEqualTo("error.task.retry_unknown_outcome_requires_resolution");
          }
        });
    assertThat(jdbc.queryForObject(selectFileDispatchRecordSql44, String.class, dispatchId))
        .isEqualTo(expectedRecordStatus);
    assertThat(jdbc.queryForObject(selectFileDispatchRecordSql45, String.class, dispatchId))
        .isEqualTo("FAILED");
    assertThat(outboxCount(scenario)).isEqualTo(beforeOutbox);
    assertThat(partition(scenario).getVersion()).isEqualTo(beforePartition.getVersion());
    assertThat(partition(scenario).getPartitionStatus())
        .isEqualTo(beforePartition.getPartitionStatus());
    assertThat(jdbc.queryForObject(selectFileAuditLogSql46, Long.class, scenario.tenant(), fileId))
        .isZero();
  }

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
    String snapshot =
        jdbc.queryForObject(selectParamsSnapshotSql, String.class, scenario.instanceId());
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
    jdbc.update(updateJobInstanceSql, status, scenario.instanceId());
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
    jdbc.update(updateWorkflowRunSql, status, scenario.workflowId());
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
      jdbc.update(updateJobInstanceSql, scenario.instanceId());
    } else {
      jdbc.update(updateWorkflowRunSql, scenario.workflowId());
    }
    JobTaskEntity claimableTask = tasks.selectById(scenario.tenant(), scenario.taskId());
    assertThat(tasks.selectForWorkerClaim(scenario.tenant(), claimableTask.getId()))
        .isNotNull();
    claim(scenario);
    jdbc.update(updateJobPartitionSql, scenario.tenant(), scenario.partitionId());
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
    assertThat(
            tasks.resetForLeaseReclaim(scenario.tenant(), task.getId(), "READY", task.getVersion()))
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
    jdbc.update(updateJobPartitionSql, scenario.tenant(), scenario.partitionId());
    Long deadLetterId = seedDueDeadLetter(scenario);
    jdbc.update(updateDeadLetterTaskSql, scenario.tenant(), deadLetterId);
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
    jdbc.update(updateJobPartitionSql, scenario.tenant(), scenario.partitionId());
    Long deadLetterId = seedDueDeadLetter(scenario);
    jdbc.update(updateDeadLetterTaskSql, scenario.tenant(), deadLetterId);
    long outboxBefore = outboxCount(scenario);

    assertThatThrownBy(() -> retries.replayDeadLetter(DeadLetterReplayInput.builder()
            .tenantId(scenario.tenant())
            .deadLetterTaskId(deadLetterId)
            .operatorId("operator-1")
            .reason("external system audit confirmed no action")
            .idempotencyKey("verified-retry-1")
            .externalActionVerifiedNotExecuted(true)
            .externalOutcomeEvidenceReference("incident://ops/INC-2048")
            .build()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining(
            "error.dead_letter.external_outcome_resolution_requires_compensation");

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
    jdbc.update(updateJobTaskSql, scenario.tenant(), scenario.taskId());
    jdbc.update(updateJobPartitionSql, scenario.tenant(), scenario.partitionId());
    Long deadLetterId = seedDueDeadLetter(scenario);
    long outboxBefore = outboxCount(scenario);

    assertThatThrownBy(() -> retries.replayDeadLetter(DeadLetterReplayInput.builder()
            .tenantId(scenario.tenant())
            .deadLetterTaskId(deadLetterId)
            .operatorId("operator-1")
            .reason("verified no external action")
            .idempotencyKey("misapplied-resolution")
            .externalActionVerifiedNotExecuted(true)
            .externalOutcomeEvidenceReference("incident://ops/INC-2049")
            .build()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining(
            "error.dead_letter.external_outcome_resolution_requires_compensation");

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
    jdbc.update(updateJobPartitionSql, scenario.tenant(), scenario.partitionId());
    Long deadLetterId = seedDueDeadLetter(scenario);
    jdbc.update(updateDeadLetterTaskSql, scenario.tenant(), deadLetterId);
    long outboxBefore = outboxCount(scenario);

    assertThatThrownBy(() -> retries.replayDeadLetter(DeadLetterReplayInput.builder()
            .tenantId(scenario.tenant())
            .deadLetterTaskId(deadLetterId)
            .operatorId("operator-1")
            .reason("verified external action was not executed")
            .idempotencyKey("mismatched-source")
            .externalActionVerifiedNotExecuted(true)
            .externalOutcomeEvidenceReference("incident://ops/INC-2050")
            .build()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining(
            "error.dead_letter.external_outcome_resolution_requires_compensation");

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
    Long earlierKnownFailureId =
        jdbc.queryForObject(insertJobTaskSql, Long.class, scenario.tenant(), scenario.taskId());
    jdbc.update(updateJobPartitionSql, scenario.tenant(), scenario.partitionId());
    Long deadLetterId = seedDueDeadLetter(scenario);
    jdbc.update(updateDeadLetterTaskSql, scenario.tenant(), deadLetterId);
    long outboxBefore = outboxCount(scenario);

    assertThatThrownBy(() -> retries.replayDeadLetter(DeadLetterReplayInput.builder()
            .tenantId(scenario.tenant())
            .deadLetterTaskId(deadLetterId)
            .operatorId("operator-1")
            .reason("external system audit confirmed no action")
            .idempotencyKey("verified-retry-multiple-failures")
            .externalActionVerifiedNotExecuted(true)
            .externalOutcomeEvidenceReference("incident://ops/INC-2051")
            .build()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining(
            "error.dead_letter.external_outcome_resolution_requires_compensation");

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
            scenario.tenant(), scenario.instanceId()))
        .isTrue();

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
            scenario.tenant(), scenario.instanceId()))
        .isFalse();

    jdbc.update(markResolutionConsumedSql, scenario.tenant(), scenario.taskId());
    assertThat(tasks.existsUnknownExternalOutcomeForJobInstance(
            scenario.tenant(), scenario.instanceId()))
        .isTrue();
    assertThat(tasks.existsUnknownExternalOutcomeForJobBizDate(
            scenario.tenant(),
            instances.selectById(scenario.tenant(), scenario.instanceId()).getJobCode(),
            BIZ_DATE))
        .isTrue();
  }

  @Test
  @DisplayName("WR-04：另一根请求不能盗用核实授权，绑定根首次开始原子消费且重复开始保持幂等")
  void shouldConsumeUnknownResolutionOnlyFromBoundWorkflowRoot() {
    Scenario source = launch("NONE", 0);
    claim(source);
    String sourceInvocation = partition(source).getCurrentInvocationId();
    boolean sourceStarted = execution.markNonIdempotentExecutionStarted(
        source.tenant(), source.taskId(), source.worker(), sourceInvocation);
    assertThat(sourceStarted).isTrue();
    execution.applyTaskOutcome(failure(source));
    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(workflowStatus(source)).isEqualTo("FAILED"));
    JobTaskEntity unknown = tasks.selectById(source.tenant(), source.taskId());
    assertThat(unknown.getErrorCode()).isEqualTo(TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN);

    String approvedRequestId = "approved-" + UUID.randomUUID();
    Long approvedRequest = prepareResolutionRootRequest(source, approvedRequestId);
    int marked =
        tasks.markExternalOutcomeResolution(source.tenant(), source.taskId(), unknown.getVersion());
    int bound = tasks.bindExternalOutcomeResolutionToWorkflowRequest(
        source.tenant(), source.taskId(), unknown.getVersion(), approvedRequest);
    assertThat(marked).isEqualTo(1);
    assertThat(bound).isEqualTo(1);
    Map<String, Object> parameters = Map.of(
        "parentInstanceId", source.instanceId(),
        "rerunFlag", true,
        "_externalOutcomeResolutionTaskId", source.taskId(),
        "_externalOutcomeResolutionVersion", unknown.getVersion());

    String unrelatedRequestId = "unrelated-" + UUID.randomUUID();
    prepareResolutionRootRequest(source, unrelatedRequestId);
    Scenario unrelated = launchResolutionRoot(source, unrelatedRequestId, parameters);
    claim(unrelated);
    boolean unrelatedStarted = execution.markNonIdempotentExecutionStarted(
        unrelated.tenant(),
        unrelated.taskId(),
        unrelated.worker(),
        partition(unrelated).getCurrentInvocationId());
    assertThat(unrelatedStarted).as("复制授权标识的另一根请求不得开始外部动作").isFalse();
    JobTaskEntity stillUnconsumed = tasks.selectById(source.tenant(), source.taskId());
    assertThat(stillUnconsumed.getExternalOutcomeResolutionConsumedAt()).isNull();
    JobTaskEntity unrelatedTask = tasks.selectById(unrelated.tenant(), unrelated.taskId());
    assertThat(unrelatedTask.getNonIdempotentStartedAt()).isNull();
    execution.applyTaskOutcome(failure(unrelated));
    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(workflowStatus(unrelated)).isEqualTo("FAILED"));

    Scenario approved = launchResolutionRoot(source, approvedRequestId, parameters);
    claim(approved);
    String invocation = partition(approved).getCurrentInvocationId();
    String faultName = "wr04_start_" + UUID.randomUUID().toString().replace("-", "");
    String createFaultFunctionSql = """
        create function batch.%s() returns trigger language plpgsql as $wr04$
        begin raise exception 'WR04 injected start write failure'; end;
        $wr04$
        """.formatted(faultName);
    String createFaultTriggerSql = """
        create trigger %s before update of non_idempotent_started_at on batch.job_task
        for each row when (new.id = %d) execute function batch.%s()
        """.formatted(faultName, approved.taskId(), faultName);
    String dropFaultTriggerSql = "drop trigger if exists %s on batch.job_task".formatted(faultName);
    String dropFaultFunctionSql = "drop function if exists batch.%s()".formatted(faultName);
    jdbc.execute(createFaultFunctionSql);
    jdbc.execute(createFaultTriggerSql);
    try {
      assertThatThrownBy(() -> execution.markNonIdempotentExecutionStarted(
              approved.tenant(), approved.taskId(), approved.worker(), invocation))
          .hasStackTraceContaining("WR04 injected start write failure");
      JobTaskEntity rolledBackSource = tasks.selectById(source.tenant(), source.taskId());
      JobTaskEntity rolledBackTarget = tasks.selectById(approved.tenant(), approved.taskId());
      assertThat(rolledBackSource.getExternalOutcomeResolutionConsumedAt()).isNull();
      assertThat(rolledBackTarget.getNonIdempotentStartedAt()).isNull();
    } finally {
      jdbc.execute(dropFaultTriggerSql);
      jdbc.execute(dropFaultFunctionSql);
    }
    boolean approvedStarted = execution.markNonIdempotentExecutionStarted(
        approved.tenant(), approved.taskId(), approved.worker(), invocation);
    assertThat(approvedStarted).isTrue();
    JobTaskEntity consumed = tasks.selectById(source.tenant(), source.taskId());
    assertThat(consumed.getExternalOutcomeResolutionConsumedAt()).isNotNull();
    assertThat(consumed.getExternalOutcomeResolutionTriggerRequestId()).isEqualTo(approvedRequest);
    boolean repeatedStart = execution.markNonIdempotentExecutionStarted(
        approved.tenant(), approved.taskId(), approved.worker(), invocation);
    assertThat(repeatedStart).isTrue();
    JobTaskEntity consumedAgain = tasks.selectById(source.tenant(), source.taskId());
    assertThat(consumedAgain.getExternalOutcomeResolutionConsumedAt())
        .isEqualTo(consumed.getExternalOutcomeResolutionConsumedAt());
    assertThat(
            tasks.existsUnknownExternalOutcomeForJobInstance(source.tenant(), source.instanceId()))
        .as("消费后原 UNKNOWN 再次阻止额外补偿，Worker 崩溃不恢复授权")
        .isTrue();
  }

  private Long prepareResolutionRootRequest(Scenario source, String requestId) {
    String insertRequestSql = """
        insert into batch.trigger_request
            (tenant_id, request_id, workflow_definition_id, trigger_type, biz_date,
             dedup_key, request_status, trace_id, dry_run)
        select tenant_id, ?, workflow_definition_id, 'API', biz_date, ?, 'ACCEPTED', ?, false
          from batch.workflow_run where tenant_id = ? and id = ? returning id
        """;
    return jdbc.queryForObject(
        insertRequestSql,
        Long.class,
        requestId,
        requestId,
        requestId,
        source.tenant(),
        source.workflowId());
  }

  private Scenario launchResolutionRoot(
      Scenario source, String requestId, Map<String, Object> parameters) {
    WorkflowRunEntity sourceRun = workflows.selectById(source.tenant(), source.workflowId());
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(source.tenant())
        .workflowDefinitionId(sourceRun.getWorkflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(requestId)
        .traceId(requestId)
        .params(parameters)
        .build();
    LaunchResponse response = launchService.launch(request);
    Long instanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbc, source.tenant(), response.instanceNo());
    var query = JobTaskQuery.ofJobInstance(source.tenant(), instanceId);
    List<JobTaskEntity> created = tasks.selectByQuery(query);
    assertThat(created).hasSize(1);
    JobTaskEntity task = created.getFirst();
    WorkflowRunEntity root = workflows.selectByNodeJobInstanceId(source.tenant(), instanceId);
    return new Scenario(
        source.tenant(),
        instanceId,
        task.getId(),
        task.getJobPartitionId(),
        root.getId(),
        source.worker());
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
    jdbc.update(updateJobPartitionSql, scenario.tenant(), scenario.partitionId());
    jdbc.update(updateJobInstanceSql, scenario.tenant(), scenario.instanceId());
    jdbc.update(updateWorkflowRunSql, scenario.tenant(), scenario.workflowId());
    String jobCode = jdbc.queryForObject(
        selectJobCodeSql, String.class, scenario.tenant(), scenario.instanceId());
    assertThat(tasks.existsUnknownExternalOutcomeForJobInstance(
            scenario.tenant(), scenario.instanceId()))
        .isTrue();
    assertThat(
            tasks.existsUnknownExternalOutcomeForJobBizDate(scenario.tenant(), jobCode, BIZ_DATE))
        .isTrue();
    assertThat(tasks.existsUnknownExternalOutcomeForJobBizDate(
            scenario.tenant(), jobCode, BIZ_DATE.plusDays(1)))
        .isFalse();
    long outboxBefore = outboxCount(scenario);
    long triggerRequestsBefore = jdbc.queryForObject(selectCountSql, Long.class, scenario.tenant());
    long instancesBefore = jdbc.queryForObject(selectCountSql2, Long.class, scenario.tenant());

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
    jdbc.update(updateWorkflowRunSql, scenario.workflowId());
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
    jdbc.update(updateWorkflowRunSql, scenario.workflowId());
    List<Map<String, Object>> before = executionRows(scenario);
    retries.dispatchDueRetries();
    assertThat(retryStatus(scenario)).isEqualTo("WAITING");
    assertThat(executionRows(scenario)).isEqualTo(before);
    jdbc.update(updateWorkflowRunSql2, scenario.workflowId());
    retries.dispatchDueRetries();
    assertThat(retryStatus(scenario)).isEqualTo("SUCCESS");
    assertThat(tasks.selectById(scenario.tenant(), scenario.taskId()).getTaskStatus())
        .isEqualTo("READY");
  }

  @Test
  @Timeout(90)
  @DisplayName("取消请求与普通失败并发时记录重试及后续认领的取消状态")
  void shouldObserveCancellationFlag_whenFailureRacesWithCancelRequest() {
    Scenario scenario = launch("FIXED", 1);
    claim(scenario);
    assertThat(execution.requestCancel(scenario.tenant(), scenario.taskId())).isTrue();
    execution.applyTaskOutcome(failure(scenario));
    assertThat(retryStatus(scenario)).isEqualTo("WAITING");
    makeRetriesDue(scenario);
    retries.dispatchDueRetries();
    assertThat(retryStatus(scenario)).isEqualTo("SUCCESS");
    claim(scenario);
    var result = execution.recordHeartbeat(
        scenario.tenant(),
        scenario.taskId(),
        scenario.worker(),
        partition(scenario).getCurrentInvocationId(),
        null);

    assertThat(result.leaseRenewed()).isTrue();
    assertThat(result.cancelRequested()).isTrue();
  }

  @Test
  @Timeout(90)
  @DisplayName("扫描后父流程暂停时重放回滚认领且恢复后继续")
  void shouldPreserveDeadLetter_whenWorkflowPausesAfterScan() {
    final String updateWorkflowSql = """
        update batch.workflow_run set run_status = ? where tenant_id = ? and id = ?
        """;
    Scenario scenario = launch("FIXED", 1);
    fail(scenario);
    Long deadLetterId = seedDueDeadLetter(scenario);
    assertThat(deadLetters.selectDueAutoRetries(20))
        .extracting(DeadLetterTaskEntity::getId)
        .contains(deadLetterId);
    long beforeOutbox = outboxCount(scenario);
    jdbc.update(updateWorkflowSql, "PAUSED", scenario.tenant(), scenario.workflowId());

    assertThatThrownBy(() -> retries.replayDeadLetter(scenario.tenant(), deadLetterId))
        .satisfies(
            ex -> assertThat(ex.getClass().getSimpleName()).isEqualTo("ExecutionPausedException"));
    DeadLetterTaskEntity paused = deadLetters.selectById(scenario.tenant(), deadLetterId);
    assertThat(paused.getReplayStatus()).isEqualTo("NEW");
    assertThat(paused.getReplayCount()).isZero();
    assertThat(outboxCount(scenario)).isEqualTo(beforeOutbox);

    jdbc.update(updateWorkflowSql, "RUNNING", scenario.tenant(), scenario.workflowId());
    retries.autoRetryDueDeadLetters();
    assertThat(deadLetters.selectById(scenario.tenant(), deadLetterId).getReplayStatus())
        .isEqualTo("SUCCESS");
  }

  @Test
  @Timeout(90)
  @DisplayName("暂停作业的死信跳过扫描，恢复后继续重放")
  void shouldRetainDueDeadLetter_whenJobPausesAndResumes() {
    final String updateJobSql = """
        update batch.job_instance set instance_status = ? where tenant_id = ? and id = ?
        """;
    Scenario scenario = launch("FIXED", 1);
    fail(scenario);
    Long deadLetterId = seedDueDeadLetter(scenario);
    jdbc.update(updateJobSql, "PAUSED", scenario.tenant(), scenario.instanceId());

    retries.autoRetryDueDeadLetters();
    assertThat(deadLetters.selectById(scenario.tenant(), deadLetterId).getReplayStatus())
        .isEqualTo("NEW");
    jdbc.update(updateJobSql, "RUNNING", scenario.tenant(), scenario.instanceId());
    retries.autoRetryDueDeadLetters();
    assertThat(deadLetters.selectById(scenario.tenant(), deadLetterId).getReplayStatus())
        .isEqualTo("SUCCESS");
  }

  @Test
  @Timeout(90)
  @DisplayName("非分区来源不会被碰巧相同的暂停分区身份过滤")
  void shouldRetainUnsupportedDeadLetter_whenSourceIdMatchesPausedPartition() {
    final String updateJobSql = """
        update batch.job_instance set instance_status = 'PAUSED' where tenant_id = ? and id = ?
        """;
    final String updateSourceSql = """
        update batch.dead_letter_task set source_type = 'MQ_MESSAGE' where tenant_id = ? and id = ?
        """;
    Scenario scenario = launch("FIXED", 1);
    fail(scenario);
    Long deadLetterId = seedDueDeadLetter(scenario);
    jdbc.update(updateJobSql, scenario.tenant(), scenario.instanceId());
    jdbc.update(updateSourceSql, scenario.tenant(), deadLetterId);

    retries.autoRetryDueDeadLetters();

    assertThat(deadLetters.selectById(scenario.tenant(), deadLetterId).getReplayStatus())
        .isEqualTo("FAILED");
  }

  @Test
  @Timeout(90)
  @DisplayName("死信关联终态流程或缺失分区时仍进入失败收口")
  void shouldCloseDueDeadLetters_whenPausedParentIsTerminalOrPartitionIsMissing() {
    final String updateJobSql = """
        update batch.job_instance set instance_status = 'PAUSED' where tenant_id = ? and id = ?
        """;
    final String updateWorkflowSql = """
        update batch.workflow_run set run_status = 'FAILED' where tenant_id = ? and id = ?
        """;
    final String updateSourceSql = """
        update batch.dead_letter_task set source_id = -1 where tenant_id = ? and id = ?
        """;
    Scenario terminal = launch("FIXED", 1);
    Scenario missing = launch("FIXED", 1);
    fail(terminal);
    fail(missing);
    Long terminalDeadLetter = seedDueDeadLetter(terminal);
    Long missingDeadLetter = seedDueDeadLetter(missing);
    jdbc.update(updateJobSql, terminal.tenant(), terminal.instanceId());
    jdbc.update(updateWorkflowSql, terminal.tenant(), terminal.workflowId());
    jdbc.update(updateSourceSql, missing.tenant(), missingDeadLetter);

    retries.autoRetryDueDeadLetters();

    assertThat(deadLetters.selectById(terminal.tenant(), terminalDeadLetter).getReplayStatus())
        .isEqualTo("GIVE_UP");
    assertThat(deadLetters.selectById(missing.tenant(), missingDeadLetter).getReplayStatus())
        .isEqualTo("GIVE_UP");
  }

  @Test
  @Timeout(90)
  @DisplayName("暂停死信占满扫描批次时，其他死信仍应推进且恢复后保留原重放")
  void shouldReplayOtherDueDeadLetter_whenEarlierDeadLetterIsPaused() {
    final String orderDeadLetterSql = """
        update batch.dead_letter_task set next_replay_at = ? where tenant_id = ? and id = ?
        """;
    final String setWorkflowStatusSql = """
        update batch.workflow_run set run_status = ? where id = ?
        """;
    Scenario paused = launch("FIXED", 1);
    Scenario active = launch("FIXED", 1);
    fail(paused);
    fail(active);
    Long pausedId = seedDueDeadLetter(paused);
    Long activeId = seedDueDeadLetter(active);
    jdbc.update(
        orderDeadLetterSql,
        Timestamp.from(Instant.parse("1900-01-01T00:00:00Z")),
        paused.tenant(),
        pausedId);
    jdbc.update(
        orderDeadLetterSql,
        Timestamp.from(Instant.parse("1900-01-02T00:00:00Z")),
        active.tenant(),
        activeId);
    jdbc.update(setWorkflowStatusSql, "PAUSED", paused.workflowId());
    int previousBatchSize = retryProperties.getBatchSize();
    retryProperties.setBatchSize(1);
    try {
      retries.autoRetryDueDeadLetters();
      retries.autoRetryDueDeadLetters();
      assertThat(deadLetter(paused).get("replay_status")).isEqualTo("NEW");
      assertThat(deadLetter(active).get("replay_status")).isEqualTo("SUCCESS");
      jdbc.update(setWorkflowStatusSql, "RUNNING", paused.workflowId());
      retries.autoRetryDueDeadLetters();
      assertThat(deadLetter(paused).get("replay_status")).isEqualTo("SUCCESS");
    } finally {
      retryProperties.setBatchSize(previousBatchSize);
    }
  }

  @Test
  @Timeout(90)
  @DisplayName("暂停重试占满扫描批次时，其他任务仍应推进且恢复后保留原重试")
  void shouldDispatchOtherDueRetry_whenEarlierRetryIsPaused() {
    final String orderRetrySql = """
        update batch.retry_schedule set next_retry_at = ? where tenant_id = ?
        """;
    final String setWorkflowStatusSql = """
        update batch.workflow_run set run_status = ? where id = ?
        """;
    Scenario paused = launch("FIXED", 1);
    Scenario active = launch("FIXED", 1);
    fail(paused);
    fail(active);
    jdbc.update(
        orderRetrySql, Timestamp.from(Instant.parse("1900-01-01T00:00:00Z")), paused.tenant());
    jdbc.update(
        orderRetrySql, Timestamp.from(Instant.parse("1900-01-02T00:00:00Z")), active.tenant());
    jdbc.update(setWorkflowStatusSql, "PAUSED", paused.workflowId());
    int previousBatchSize = retryProperties.getBatchSize();
    retryProperties.setBatchSize(1);
    try {
      retries.dispatchDueRetries();
      retries.dispatchDueRetries();
      assertThat(retryStatus(paused)).isEqualTo("WAITING");
      assertThat(retryStatus(active)).isEqualTo("SUCCESS");
      jdbc.update(setWorkflowStatusSql, "RUNNING", paused.workflowId());
      retries.dispatchDueRetries();
      assertThat(retryStatus(paused)).isEqualTo("SUCCESS");
    } finally {
      retryProperties.setBatchSize(previousBatchSize);
    }
  }

  @Test
  @Timeout(90)
  @DisplayName("父作业终态或分区缺失时仍须进入失败收口，不能被暂停扫描过滤")
  void shouldCloseInvalidDueRetry_whenWorkflowPausedOrPartitionMissing() {
    final String terminalInstanceSql = """
        update batch.job_instance set instance_status = 'FAILED' where tenant_id = ? and id = ?
        """;
    final String pausedWorkflowSql = """
        update batch.workflow_run set run_status = 'PAUSED' where id = ?
        """;
    final String setDueSql = """
        update batch.retry_schedule set next_retry_at = ? where tenant_id = ?
        """;
    final String removePartitionReferenceSql = """
        update batch.retry_schedule set related_id = -1 where tenant_id = ?
        """;
    Scenario terminal = launch("FIXED", 1);
    Scenario missing = launch("FIXED", 1);
    fail(terminal);
    fail(missing);
    jdbc.update(terminalInstanceSql, terminal.tenant(), terminal.instanceId());
    jdbc.update(pausedWorkflowSql, terminal.workflowId());
    jdbc.update(removePartitionReferenceSql, missing.tenant());
    jdbc.update(
        setDueSql, Timestamp.from(Instant.parse("1900-01-01T00:00:00Z")), terminal.tenant());
    jdbc.update(setDueSql, Timestamp.from(Instant.parse("1900-01-02T00:00:00Z")), missing.tenant());
    int previousBatchSize = retryProperties.getBatchSize();
    retryProperties.setBatchSize(1);
    try {
      retries.dispatchDueRetries();
      retries.dispatchDueRetries();
      assertThat(retryStatus(terminal)).isEqualTo("FAILED");
      assertThat(retryStatus(missing)).isEqualTo("FAILED");
    } finally {
      retryProperties.setBatchSize(previousBatchSize);
    }
  }

  @Test
  @Timeout(90)
  @DisplayName("作业暂停但父 Workflow 已终结时重试应失败收口，无关联 Workflow 则继续等待")
  void shouldClosePausedJobRetry_whenParentWorkflowTerminates() {
    final String pauseInstanceSql = """
        update batch.job_instance set instance_status = 'PAUSED' where tenant_id = ? and id = ?
        """;
    final String terminateWorkflowSql = """
        update batch.workflow_run set run_status = 'TERMINATED' where id = ?
        """;
    final String unlinkWorkflowSql = """
        update batch.workflow_node_run set job_instance_id = null
        where workflow_run_id = ? and job_instance_id = ?
        """;
    Scenario terminated = launch("FIXED", 1);
    Scenario detached = launch("FIXED", 1);
    fail(terminated);
    fail(detached);
    makeRetriesDue(terminated);
    makeRetriesDue(detached);
    jdbc.update(pauseInstanceSql, terminated.tenant(), terminated.instanceId());
    jdbc.update(pauseInstanceSql, detached.tenant(), detached.instanceId());
    jdbc.update(terminateWorkflowSql, terminated.workflowId());
    assertThat(jdbc.update(unlinkWorkflowSql, detached.workflowId(), detached.instanceId()))
        .isEqualTo(1);

    RetryScheduleQuery terminatedQuery =
        new RetryScheduleQuery(terminated.tenant(), "WAITING", Instant.now(), 1);
    RetryScheduleQuery detachedQuery =
        new RetryScheduleQuery(detached.tenant(), "WAITING", Instant.now(), 1);
    assertThat(retrySchedules.selectDueNotPaused(terminatedQuery)).hasSize(1);
    assertThat(retrySchedules.selectDueNotPaused(detachedQuery)).isEmpty();
    retries.dispatchDueRetries();
    assertThat(retryStatus(terminated)).isEqualTo("FAILED");
    assertThat(retryStatus(detached)).isEqualTo("WAITING");
  }

  @Test
  @Timeout(90)
  @DisplayName("不同关联类型的 ID 与暂停分片碰撞时仍应进入扫描，通用查询仍包含暂停计划")
  void shouldKeepNonPartitionRetryAndGeneralQuery_whenIdMatchesPausedPartition() {
    final String updateRetrySql = """
        update batch.retry_schedule set next_retry_at = now() - interval '1 second'
        where tenant_id = ?
        """;
    final String updateRelatedTypeSql = """
        update batch.retry_schedule set related_type = 'JOB_TASK' where tenant_id = ?
        """;
    final String pauseInstanceSql = """
        update batch.job_instance set instance_status = 'PAUSED' where tenant_id = ? and id = ?
        """;
    Scenario otherType = launch("FIXED", 1);
    fail(otherType);
    jdbc.update(updateRetrySql, otherType.tenant());
    jdbc.update(pauseInstanceSql, otherType.tenant(), otherType.instanceId());
    RetryScheduleQuery query =
        new RetryScheduleQuery(otherType.tenant(), "WAITING", Instant.now(), 1);

    assertThat(retrySchedules.selectDueNotPaused(query)).isEmpty();
    assertThat(retrySchedules.selectByQuery(query)).hasSize(1);
    jdbc.update(updateRelatedTypeSql, otherType.tenant());
    assertThat(retrySchedules.selectDueNotPaused(query)).hasSize(1);
  }

  @Test
  @Timeout(40)
  @DisplayName("同一未知外部结果源任务的两条死信并发核实重放只创建一个新 Workflow 请求")
  void shouldFenceConcurrentVerifiedReplaysAcrossTwoDeadLettersForSameSourceTask()
      throws Exception {
    Scenario scenario = launch("NONE", 0);
    claim(scenario);
    JobPartitionEntity sourcePartition = partition(scenario);
    TaskOutcomeCommand unknown = TaskOutcomeCommand.builder()
        .tenantId(scenario.tenant())
        .taskId(scenario.taskId())
        .workerId(scenario.worker())
        .success(false)
        .errorCode(TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN)
        .errorMessage("external system response was lost")
        .partitionInvocationId(sourcePartition.getCurrentInvocationId())
        .build();
    execution.applyTaskOutcome(unknown);
    Map<String, Object> firstDeadLetter = deadLetter(scenario);
    Long firstId = ((Number) firstDeadLetter.get("id")).longValue();
    Long secondId = seedDueDeadLetter(scenario);
    JobTaskEntity before = tasks.selectById(scenario.tenant(), scenario.taskId());
    long outboxBefore = outboxCount(scenario);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    Function<Long, Callable<Boolean>> attempt = deadLetterId -> () -> {
      ready.countDown();
      if (!start.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("concurrent replay start was not released");
      }
      CompensationSubmitCommand command = CompensationSubmitCommand.builder()
          .tenantId(scenario.tenant())
          .compensationType("DLQ")
          .targetId(deadLetterId)
          .operatorId("verified-operator")
          .reason("外部系统核对确认未执行")
          .externalActionVerifiedNotExecuted(true)
          .externalOutcomeEvidenceReference("incident://verified-" + deadLetterId)
          .build();
      try {
        RlsTenantContextHolder.runWithTenant(scenario.tenant(), () -> compensation.submit(command));
        return true;
      } catch (BizException failure) {
        assertThat(failure.getCode()).isEqualTo(ResultCode.CONFLICT);
        return false;
      }
    };
    boolean firstSucceeded;
    boolean secondSucceeded;
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> firstAttempt = attempt.apply(firstId);
      Callable<Boolean> secondAttempt = attempt.apply(secondId);
      Future<Boolean> first = executor.submit(firstAttempt);
      Future<Boolean> second = executor.submit(secondAttempt);
      boolean bothReady = ready.await(5, TimeUnit.SECONDS);
      assertThat(bothReady).isTrue();
      start.countDown();
      firstSucceeded = first.get(20, TimeUnit.SECONDS);
      secondSucceeded = second.get(20, TimeUnit.SECONDS);
    }
    int successes = (firstSucceeded ? 1 : 0) + (secondSucceeded ? 1 : 0);
    JobTaskEntity after = tasks.selectById(scenario.tenant(), scenario.taskId());
    long outboxAfter = outboxCount(scenario);
    final String countStatusSql = """
        select count(*) from batch.dead_letter_task
        where tenant_id = ? and replay_status = ?
        """;
    Long successfulDeadLetters =
        jdbc.queryForObject(countStatusSql, Long.class, scenario.tenant(), "SUCCESS");
    Long pendingDeadLetters =
        jdbc.queryForObject(countStatusSql, Long.class, scenario.tenant(), "NEW");
    assertThat(successes).isEqualTo(1);
    assertThat(successfulDeadLetters).isEqualTo(1);
    assertThat(pendingDeadLetters).isEqualTo(1);
    assertThat(after.getVersion()).isEqualTo(before.getVersion() + 1);
    assertThat(after.getExternalOutcomeResolutionVersion()).isEqualTo(before.getVersion());
    assertThat(after.getExternalOutcomeResolutionTriggerRequestId()).isNotNull();
    assertThat(after.getExternalOutcomeResolutionConsumedAt()).isNull();
    assertThat(outboxAfter).isEqualTo(outboxBefore + 1);
  }

  @Test
  @DisplayName("陈旧自动重试快照不能把已成功重放的死信改成放弃")
  void shouldKeepSuccessfulReplayTerminal_whenStaleGiveUpArrives() {
    Scenario scenario = launch("NONE", 0);
    Long deadLetterId = seedDueDeadLetter(scenario);
    retries.replayDeadLetter(scenario.tenant(), deadLetterId);
    Map<String, Object> before = deadLetter(scenario);
    assertThat(before.get("replay_status")).isEqualTo("SUCCESS");

    int changed = deadLetters.markGiveUp(scenario.tenant(), deadLetterId);

    Map<String, Object> after = deadLetter(scenario);
    assertThat(changed).isZero();
    assertThat(after.get("replay_status")).isEqualTo("SUCCESS");
    assertThat(after.get("replay_count")).isEqualTo(before.get("replay_count"));
  }

  @Test
  @DisplayName("死信补偿成功时业务复位、Outbox 和补偿成功状态共同提交")
  void shouldCommitDeadLetterReplayWithCompensationSuccess() {
    Scenario scenario = launch("NONE", 0);
    Long deadLetterId = seedDueDeadLetter(scenario);
    long before = outboxCount(scenario);
    CompensationSubmitCommand command = CompensationSubmitCommand.builder()
        .tenantId(scenario.tenant())
        .compensationType("DLQ")
        .targetId(deadLetterId)
        .operatorId("integration-operator")
        .reason("验证共同提交")
        .build();
    String commandNo = compensation.submit(command);
    final String selectStatusSql = """
        select command_status from batch.compensation_command
        where tenant_id = ? and command_no = ?
        """;
    String commandStatus =
        jdbc.queryForObject(selectStatusSql, String.class, scenario.tenant(), commandNo);
    Map<String, Object> actualDeadLetter = deadLetter(scenario);
    long actualOutboxCount = outboxCount(scenario);
    assertThat(commandStatus).isEqualTo("SUCCESS");
    assertThat(actualDeadLetter.get("replay_status")).isEqualTo("SUCCESS");
    assertThat(actualOutboxCount).isEqualTo(before + 1);
  }

  @Test
  @DisplayName("补偿成功记账失败时死信重放与 Outbox 必须一起回滚")
  void shouldRollbackDeadLetterReplay_whenCompensationSuccessRecordingFails() {
    Scenario scenario = launch("NONE", 0);
    Long deadLetterId = seedDueDeadLetter(scenario);
    List<Map<String, Object>> before = executionRows(scenario);
    long outboxBefore = outboxCount(scenario);
    doThrow(new IllegalStateException("SIM_COMPENSATION_SUCCESS_RECORD_FAILED"))
        .when(compensationCommands)
        .updateStatus(ArgumentMatchers.argThat(param -> param != null
            && scenario.tenant().equals(param.getTenantId())
            && "SUCCESS".equals(param.getCommandStatus())));
    CompensationSubmitCommand command = CompensationSubmitCommand.builder()
        .tenantId(scenario.tenant())
        .compensationType("DLQ")
        .targetId(deadLetterId)
        .operatorId("integration-operator")
        .reason("验证补偿记账与业务重放的共同回滚")
        .build();

    assertThatThrownBy(() -> compensation.submit(command))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("SIM_COMPENSATION_SUCCESS_RECORD_FAILED");

    List<Map<String, Object>> actualExecutionRows = executionRows(scenario);
    long actualOutboxCount = outboxCount(scenario);
    Map<String, Object> actualDeadLetter = deadLetter(scenario);
    final String selectCommandStatusSql = """
        select command_status from batch.compensation_command
        where tenant_id = ? and compensation_type = 'DLQ' and target_id = ?
        """;
    String actualCommandStatus =
        jdbc.queryForObject(selectCommandStatusSql, String.class, scenario.tenant(), deadLetterId);
    assertThat(actualCommandStatus).isEqualTo("FAILED");
    assertThat(actualExecutionRows).isEqualTo(before);
    assertThat(actualOutboxCount).isEqualTo(outboxBefore);
    assertThat(actualDeadLetter.get("replay_status")).isEqualTo("NEW");
    assertThat(actualDeadLetter.get("replay_count")).isEqualTo(0);
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
        .writeDispatchEvent(ArgumentMatchers.argThat(command ->
            command != null && (scenario.tenant().equals(command.jobInstance().getTenantId()))));

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
    jdbc.update(updateDeadLetterTaskSql, scenario.tenant());
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
      jdbc.update(updateJobInstanceSql, scenario.instanceId());
      CompletableFuture<Void> future = CompletableFuture.runAsync(retries::dispatchDueRetries);
      Awaitility.await()
          .atMost(10, TimeUnit.SECONDS)
          .until(() -> jdbc.queryForObject(selectCountSql, Long.class) > 0);
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
    jdbc.update(updateJobDefinitionSql, retryPolicy, retryBudget, tenant, seed.jobCode());
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

    jdbc.update(updateRetryScheduleSql, scenario.tenant());
  }

  private String retryStatus(Scenario scenario) {
    final String selectRetryStatusSql = """
        select retry_status from batch.retry_schedule where tenant_id = ?
    """;

    return jdbc.queryForObject(selectRetryStatusSql, String.class, scenario.tenant());
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

    return jdbc.queryForObject(selectCountSql, Long.class, scenario.tenant());
  }

  private Map<String, Object> deadLetter(Scenario scenario) {
    final String selectStatementSql = """
        select * from batch.dead_letter_task where tenant_id = ?
    """;

    return jdbc.queryForMap(selectStatementSql, scenario.tenant());
  }

  private List<Map<String, Object>> executionRows(Scenario scenario) {
    final String selectStatementSql = """
        select 'task' as kind, id, task_status as status, version from batch.job_task where tenant_id = ?
        union all select 'partition', id, partition_status, version from batch.job_partition where tenant_id = ?
        union all select 'step', id, step_status, version from batch.job_step_instance where tenant_id = ?
        order by kind, id
        """;

    return jdbc.queryForList(
        selectStatementSql, scenario.tenant(), scenario.tenant(), scenario.tenant());
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

    return jdbc.queryForObject(
        insertDeadLetterTaskSql,
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
