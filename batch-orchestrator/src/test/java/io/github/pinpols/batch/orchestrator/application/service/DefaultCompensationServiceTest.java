package io.github.pinpols.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.RunMode;
import io.github.pinpols.batch.common.event.DomainEvent;
import io.github.pinpols.batch.common.event.DomainEventPublisher;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.governance.CompensationTransactionExecutor;
import io.github.pinpols.batch.orchestrator.application.service.governance.DefaultCompensationService;
import io.github.pinpols.batch.orchestrator.application.service.governance.FileGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.governance.RelatedFileReferenceValidator;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.governance.WorkflowNodeReplayTargetResolver;
import io.github.pinpols.batch.orchestrator.application.service.replay.WorkflowNodeReplayCommand;
import io.github.pinpols.batch.orchestrator.application.service.replay.WorkflowNodeReplayOperationService;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskAssignmentService;
import io.github.pinpols.batch.orchestrator.domain.command.CompensationSubmitCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.ApprovalCommandEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.CompensationCommandEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationPreviewTokenEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateCompensationStatusParam;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.ApprovalCommandMapper;
import io.github.pinpols.batch.orchestrator.mapper.CompensationCommandMapper;
import io.github.pinpols.batch.orchestrator.mapper.DeadLetterTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.ReplayOperationMapper;
import io.github.pinpols.batch.orchestrator.mapper.ReplayOperationPreviewTokenMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 单元测试：{@link DefaultCompensationService} 的校验与守卫条件。 */
@SuppressWarnings("unchecked")
@DisplayName("补偿服务: 提交参数校验与各类补偿路径的执行口径")
class DefaultCompensationServiceTest {

  private CompensationCommandMapper compensationCommandMapper;
  private ApprovalCommandMapper approvalCommandMapper;
  private OrchestratorJobMappers jobMappers;
  private RetryGovernanceService retryGovernanceService;
  private FileGovernanceService fileGovernanceService;
  private DomainEventPublisher domainEventPublisher;
  private OrchestratorConfigCacheService configCacheService;
  private TaskAssignmentService taskAssignmentService;
  private RelatedFileReferenceValidator relatedFileReferenceValidator;
  private WorkflowNodeReplayTargetResolver workflowNodeReplayTargetResolver;
  private ReplayOperationMapper replayOperationMapper;
  private DefaultCompensationService service;

  @BeforeEach
  void setUp() {
    compensationCommandMapper = mock(CompensationCommandMapper.class);
    Mockito.doAnswer(call -> {
          CompensationCommandEntity entity = call.getArgument(0);
          entity.setId(1L);
          return 1;
        })
        .when(compensationCommandMapper)
        .insert(any(CompensationCommandEntity.class));
    when(compensationCommandMapper.lockRunningCommand(anyString(), anyLong(), anyString()))
        .thenReturn(1L);
    when(compensationCommandMapper.updateStatus(any(UpdateCompensationStatusParam.class)))
        .thenReturn(1);
    approvalCommandMapper = mock(ApprovalCommandMapper.class);
    jobMappers = new OrchestratorJobMappers(
        mock(JobInstanceMapper.class),
        mock(JobPartitionMapper.class),
        mock(JobTaskMapper.class),
        mock(JobStepInstanceMapper.class),
        mock(TriggerRequestMapper.class));
    retryGovernanceService = mock(RetryGovernanceService.class);
    fileGovernanceService = mock(FileGovernanceService.class);
    domainEventPublisher = mock(DomainEventPublisher.class);
    configCacheService = mock(OrchestratorConfigCacheService.class);
    taskAssignmentService = mock(TaskAssignmentService.class);
    relatedFileReferenceValidator = mock(RelatedFileReferenceValidator.class);
    workflowNodeReplayTargetResolver = mock(WorkflowNodeReplayTargetResolver.class);
    replayOperationMapper = mock(ReplayOperationMapper.class);

    service = new DefaultCompensationService(
        compensationCommandMapper,
        approvalCommandMapper,
        mock(DeadLetterTaskMapper.class),
        jobMappers,
        retryGovernanceService,
        fileGovernanceService,
        domainEventPublisher,
        configCacheService,
        taskAssignmentService,
        new CompensationTransactionExecutor(new PlatformTransactionManager() {
          @Override
          public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
          }

          @Override
          public void commit(TransactionStatus status) {}

          @Override
          public void rollback(TransactionStatus status) {}
        }),
        relatedFileReferenceValidator,
        workflowNodeReplayTargetResolver,
        replayOperationMapper);
  }

  // ── validate() ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("调用方事务不可注册同步时，在创建补偿命令前失败关闭")
  void shouldRejectActiveTransactionWithoutSynchronization_beforeCreatingCommand() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> service.submit(command("t1", "PARTITION", 1L)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("transaction synchronization");
      verify(compensationCommandMapper, Mockito.never()).insert(any());
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }

  @Test
  @DisplayName("补偿提交对象为空时抛出业务异常")
  void shouldThrowWhenCommandIsNull() {
    assertThatThrownBy(() -> service.submit(null)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("开始前命令已终结时不执行补偿或覆盖对账结果")
  void shouldRejectBeforeHandler_whenCommandIsNoLongerRunning() {
    when(compensationCommandMapper.lockRunningCommand(anyString(), anyLong(), anyString()))
        .thenReturn(null);

    assertThatThrownBy(() -> service.submit(command("t1", "PARTITION", 1L)))
        .isInstanceOf(BizException.class)
        .hasMessage("error.compensation.command_not_running");
    verify(retryGovernanceService, Mockito.never())
        .retryPartition(anyString(), anyLong(), anyString());
    verify(compensationCommandMapper, Mockito.never()).updateStatus(any());
  }

  @Test
  @DisplayName("租户标识为空白时提交抛出业务异常")
  void shouldThrowWhenTenantIdIsBlank() {
    CompensationSubmitCommand cmd = command("", "JOB", 1L);
    assertThatThrownBy(() -> service.submit(cmd)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("补偿类型为空白时提交抛出业务异常")
  void shouldThrowWhenCompensationTypeIsBlank() {
    CompensationSubmitCommand cmd = command("t1", "", 1L);
    assertThatThrownBy(() -> service.submit(cmd)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("跨租户关联文件在补偿命令落库前被拒绝")
  void shouldRejectForeignTenantRelatedFileBeforeInsert() {
    CompensationSubmitCommand cmd = CompensationSubmitCommand.builder()
        .tenantId("t1")
        .compensationType("BATCH")
        .relatedFileId(99L)
        .build();
    Mockito.doThrow(BizException.of(ResultCode.NOT_FOUND, "error.file.record_not_found"))
        .when(relatedFileReferenceValidator)
        .validate("t1", 99L);

    assertThatThrownBy(() -> service.submit(cmd))
        .isInstanceOf(BizException.class)
        .hasMessage("error.file.record_not_found");

    Mockito.verify(compensationCommandMapper, Mockito.never()).insert(Mockito.any());
  }

  @Test
  @DisplayName("补偿类型不在支持范围内时提交抛出业务异常")
  void shouldThrowWhenCompensationTypeIsUnsupported() {
    // UNKNOWN type → execute() switch default → BizException
    CompensationSubmitCommand cmd = command("t1", "UNKNOWN_TYPE", 1L);
    // compensationCommandMapper.countRunningByTarget returns 0, then insert, then execute throws
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), anyLong(), anyString()))
        .thenReturn(0);

    assertThatThrownBy(() -> service.submit(cmd)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("分区类补偿缺少目标标识时抛出业务异常")
  void shouldThrowWhenPartitionTargetIdIsNull() {
    CompensationSubmitCommand cmd = command("t1", "PARTITION", null);
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), any(), anyString()))
        .thenReturn(0);

    assertThatThrownBy(() -> service.submit(cmd)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("步骤类补偿缺少目标标识时抛出业务异常")
  void shouldThrowWhenStepTargetIdIsNull() {
    CompensationSubmitCommand cmd = command("t1", "STEP", null);
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), any(), anyString()))
        .thenReturn(0);

    assertThatThrownBy(() -> service.submit(cmd)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("死信类补偿缺少目标标识时抛出业务异常")
  void shouldThrowWhenDlqTargetIdIsNull() {
    CompensationSubmitCommand cmd = command("t1", "DLQ", null);
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), any(), anyString()))
        .thenReturn(0);

    assertThatThrownBy(() -> service.submit(cmd)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("批量类补偿缺少任务编码时抛出业务异常")
  void shouldThrowWhenBatchJobCodeIsBlank() {
    CompensationSubmitCommand cmd = new CompensationSubmitCommand(
        "t1",
        "BATCH",
        null,
        null,
        null,
        LocalDate.now(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), any(), anyString()))
        .thenReturn(0);

    assertThatThrownBy(() -> service.submit(cmd)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("文件类补偿缺少目标文件标识时抛出业务异常")
  void shouldThrowWhenFileTargetIdAndRelatedFileIdAreNull() {
    CompensationSubmitCommand cmd = new CompensationSubmitCommand(
        "t1", "FILE", null, null, null, null, null, null, "CH1", null, null, null, null, null);
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), any(), anyString()))
        .thenReturn(0);

    assertThatThrownBy(() -> service.submit(cmd)).isInstanceOf(BizException.class);
  }

  // ── happy path ────────────────────────────────────────────────────────────
  // V5-P2-4: compensation 6 类 source_type happy-path 验证（PARTITION / STEP / DLQ / FILE / JOB /
  // BATCH）。
  // submit() 返回 commandNo (String)；happy-path 通过 verify() 校验侧效（确认 handler
  // 真正调到对应下游服务），不依赖返回值结构。

  @Test
  @DisplayName("分区类补偿成功时返回非空补偿单号并触发分区重试")
  void shouldRetryPartitionSuccessfully_PARTITION_path() {
    CompensationSubmitCommand cmd = command("t1", "PARTITION", 10L);
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), any(), anyString()))
        .thenReturn(0);

    String commandNo = service.submit(cmd);

    assertThat(commandNo).isNotBlank();
    verify(retryGovernanceService)
        .retryPartition(Mockito.eq("t1"), Mockito.eq(10L), Mockito.anyString());
  }

  @Test
  @DisplayName("死信类补偿成功时触发死信重放")
  void shouldReplayDeadLetterSuccessfully_DLQ_path() {
    CompensationSubmitCommand cmd = command("t1", "DLQ", 999L);
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), any(), anyString()))
        .thenReturn(0);

    String commandNo = service.submit(cmd);

    assertThat(commandNo).isNotBlank();
    verify(retryGovernanceService).replayDeadLetterInCurrentTransaction("t1", 999L);
  }

  @Test
  @DisplayName("步骤类补偿成功时按目标步骤触发重跑")
  void shouldRerunStepSuccessfully_STEP_path() {
    CompensationSubmitCommand cmd = command("t1", "STEP", 5L);
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), any(), anyString()))
        .thenReturn(0);

    JobStepInstanceEntity step = new JobStepInstanceEntity();
    step.setId(5L);
    step.setJobInstanceId(100L);
    step.setJobTaskId(50L);
    when(jobMappers.jobStepInstanceMapper.selectById("t1", 5L)).thenReturn(step);

    String commandNo = service.submit(cmd);

    assertThat(commandNo).isNotBlank();
    verify(retryGovernanceService)
        .retryTask(Mockito.eq("t1"), Mockito.eq(50L), Mockito.anyString());
  }

  @Test
  @DisplayName("作业已归档时补偿失败仍落库并保留无作业关联的审计")
  void shouldRecordFailureWithoutHotJobWhenArchiveWins() {
    JobStepInstanceEntity step = new JobStepInstanceEntity();
    step.setId(5L);
    step.setJobInstanceId(100L);
    step.setJobTaskId(50L);
    when(jobMappers.jobStepInstanceMapper.selectById("t1", 5L)).thenReturn(step);
    Mockito.doThrow(new IllegalStateException("retry failed"))
        .when(retryGovernanceService)
        .retryTask(eq("t1"), eq(50L), anyString());
    when(compensationCommandMapper.updateStatus(any(UpdateCompensationStatusParam.class)))
        .thenReturn(0, 1);

    assertThatThrownBy(() -> service.submit(command("t1", "STEP", 5L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("retry failed");

    var updates = ArgumentCaptor.forClass(UpdateCompensationStatusParam.class);
    verify(compensationCommandMapper, Mockito.times(2)).updateStatus(updates.capture());
    assertThat(updates.getAllValues())
        .extracting(UpdateCompensationStatusParam::getRelatedJobInstanceId)
        .containsExactly(100L, null);
    var logs = ArgumentCaptor.forClass(JobExecutionLogEntity.class);
    verify(taskAssignmentService).appendLog(logs.capture());
    assertThat(logs.getValue().getJobInstanceId()).isNull();
    assertThat(logs.getValue().getLogType()).isEqualTo("COMPENSATION");
  }

  @Test
  @DisplayName("文件类补偿成功时触发文件重新派发")
  void shouldReprocessFileSuccessfully_FILE_path() {
    CompensationSubmitCommand cmd = new CompensationSubmitCommand(
        "t1",
        "FILE",
        null,
        null,
        null,
        null,
        null,
        777L, // relatedFileId
        "CH-A",
        "reprocess test",
        "op-001",
        null,
        null,
        null);
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), any(), anyString()))
        .thenReturn(0);
    when(fileGovernanceService.redispatchFile(any())).thenReturn("file-redispatched-ok");

    String commandNo = service.submit(cmd);

    assertThat(commandNo).isNotBlank();
    verify(fileGovernanceService).redispatchFile(any());
  }

  @Test
  @DisplayName("作业类补偿成功时拉起新实例并登记触发请求")
  void shouldRerunJobSuccessfully_JOB_path() {
    CompensationSubmitCommand cmd = command("t1", "JOB", 100L);
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), any(), anyString()))
        .thenReturn(0);

    JobInstanceEntity sourceInstance = new JobInstanceEntity();
    sourceInstance.setId(100L);
    sourceInstance.setInstanceNo("inst-100");
    sourceInstance.setJobCode("JOB_CODE");
    sourceInstance.setBizDate(LocalDate.now());
    sourceInstance.setBatchNo("batch-100");
    sourceInstance.setTraceId("trace-source");
    when(jobMappers.jobInstanceMapper.selectById("t1", 100L)).thenReturn(sourceInstance);
    stubManagedWorkflow("JOB_CODE", 10L);

    JobInstanceEntity launched = new JobInstanceEntity();
    launched.setId(101L);
    launched.setInstanceNo("inst-101");
    when(jobMappers.jobInstanceMapper.selectCurrentByWorkflowRequest(
            ArgumentMatchers.eq("t1"), anyString(), ArgumentMatchers.eq("JOB_CODE")))
        .thenReturn(launched);

    String commandNo = service.submit(cmd);

    assertThat(commandNo).isNotBlank();
    ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
    verify(domainEventPublisher).publish(eventCaptor.capture());
    LaunchRequest acceptedLaunch = JsonUtils.fromJson(
        JsonUtils.toJson(eventCaptor.getValue().payload().get("launchRequest")),
        LaunchRequest.class);
    assertThat(acceptedLaunch.workflowDefinitionId()).isEqualTo(110L);
    assertThat(acceptedLaunch.jobCode()).isNull();
    ArgumentCaptor<TriggerRequestEntity> triggerCaptor =
        ArgumentCaptor.forClass(TriggerRequestEntity.class);
    verify(jobMappers.triggerRequestMapper).insert(triggerCaptor.capture());
    assertThat(triggerCaptor.getValue().getWorkflowDefinitionId()).isEqualTo(110L);
    assertThat(triggerCaptor.getValue().getJobCode()).isNull();
  }

  @Test
  @DisplayName("失败 JOB 节点重放固定精确来源并启动新的单作业 Workflow")
  void shouldReplayFailedWorkflowJobNodeByExactSourceIdentity() {
    LocalDate bizDate = LocalDate.of(2026, 10, 20);
    CompensationSubmitCommand command = CompensationSubmitCommand.builder()
        .tenantId("t1")
        .compensationType("WORKFLOW_NODE")
        .targetId(82L)
        .jobCode("IMPORT_CUSTOMERS")
        .bizDate(bizDate)
        .sourceWorkflowRunId(71L)
        .sourceWorkflowNodeRunId(82L)
        .reason("rerun failed node")
        .operatorId("operator-1")
        .approvalId("APR-NODE-1")
        .resultPolicy("CREATE_NEW_VERSION")
        .configVersionPolicy("USE_ORIGINAL_CONFIG")
        .traceId("trace-source-node")
        .build();
    ApprovalCommandEntity approval = new ApprovalCommandEntity();
    approval.setRequesterId("requester-1");
    approval.setApproverId("approver-1");
    when(approvalCommandMapper.selectByTenantAndApprovalNo("t1", "APR-NODE-1"))
        .thenReturn(approval);
    when(compensationCommandMapper.countRunningByTarget("t1", "WORKFLOW_NODE", 82L, "RUNNING"))
        .thenReturn(0);

    WorkflowRunEntity sourceRun = new WorkflowRunEntity();
    sourceRun.setId(71L);
    sourceRun.setTenantId("t1");
    sourceRun.setBizDate(bizDate);
    sourceRun.setRunStatus("FAILED");
    WorkflowNodeRunEntity sourceNode = new WorkflowNodeRunEntity();
    sourceNode.setId(82L);
    sourceNode.setWorkflowRunId(71L);
    sourceNode.setNodeCode("IMPORT_NODE");
    sourceNode.setNodeType("JOB");
    sourceNode.setRunSeq(2);
    sourceNode.setNodeStatus("FAILED");
    sourceNode.setJobInstanceId(93L);
    JobInstanceEntity sourceJob = new JobInstanceEntity();
    sourceJob.setId(93L);
    sourceJob.setTenantId("t1");
    sourceJob.setJobCode("IMPORT_CUSTOMERS");
    sourceJob.setInstanceNo("source-93");
    sourceJob.setBizDate(bizDate);
    sourceJob.setInstanceStatus("FAILED");
    when(workflowNodeReplayTargetResolver.resolve("t1", 71L, 82L))
        .thenReturn(new WorkflowNodeReplayTargetResolver.WorkflowNodeReplayTarget(
            sourceRun, sourceNode, sourceJob));
    when(jobMappers.jobInstanceMapper.selectById("t1", 93L)).thenReturn(sourceJob);
    stubManagedWorkflow("IMPORT_CUSTOMERS", 10L);

    JobInstanceEntity launchedJob = new JobInstanceEntity();
    launchedJob.setId(101L);
    launchedJob.setInstanceNo("rerun-101");
    when(jobMappers.jobInstanceMapper.selectCurrentByWorkflowRequest(
            eq("t1"), anyString(), eq("IMPORT_CUSTOMERS")))
        .thenReturn(launchedJob);
    ReplayOperationPreviewTokenMapper previewTokenMapper =
        mock(ReplayOperationPreviewTokenMapper.class);
    AtomicReference<ReplayOperationPreviewTokenEntity> previewToken = new AtomicReference<>();
    AtomicReference<ReplayOperationEntity> pendingOperation = new AtomicReference<>();
    when(previewTokenMapper.insert(any())).thenAnswer(invocation -> {
      previewToken.set(invocation.getArgument(0));
      return 1;
    });
    when(previewTokenMapper.selectForUpdate(eq("t1"), any()))
        .thenAnswer(invocation -> previewToken.get());
    when(previewTokenMapper.consume(eq("t1"), any(), any())).thenReturn(1);
    when(previewTokenMapper.linkOperation(eq("t1"), any(), eq(500L))).thenReturn(1);
    WorkflowNodeReplayOperationService replayOperationService =
        new WorkflowNodeReplayOperationService(
            workflowNodeReplayTargetResolver,
            replayOperationMapper,
            previewTokenMapper,
            new BatchDateTimeSupport(
                Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties())));
    WorkflowNodeReplayCommand previewCommand = WorkflowNodeReplayCommand.builder()
        .tenantId("t1")
        .workflowRunId(71L)
        .workflowNodeRunId(82L)
        .reason("rerun failed node")
        .requestedBy("operator-1")
        .resultPolicy("CREATE_NEW_VERSION")
        .configVersionPolicy("USE_ORIGINAL_CONFIG")
        .configVersion(null)
        .traceId("trace-source-node")
        .operationKey(null)
        .previewToken(null)
        .build();
    var preview = replayOperationService.preview(previewCommand);
    when(replayOperationMapper.selectByIdentity(
            eq("t1"), eq(preview.operationKey().toString())))
        .thenAnswer(invocation -> pendingOperation.get() == null
            ? null
            : pendingOperation.get().toBuilder().id(500L).build());
    when(replayOperationMapper.insert(any())).thenAnswer(invocation -> {
      pendingOperation.set(invocation.getArgument(0));
      return 1;
    });
    when(replayOperationMapper.selectForUpdate("t1", 500L))
        .thenAnswer(invocation -> pendingOperation.get().toBuilder().id(500L).build());
    when(replayOperationMapper.startApprovedWorkflowNodeReplay(any())).thenReturn(1);
    when(replayOperationMapper.selectById("t1", 500L))
        .thenAnswer(invocation -> pendingOperation.get().toBuilder()
            .id(500L)
            .status("RUNNING")
            .targetRequestId("workflow-request-1")
            .approvedBy("approver-1")
            .inFlightCount(1)
            .build());
    var pending = replayOperationService.submitForApproval(WorkflowNodeReplayCommand.builder()
        .tenantId("t1")
        .workflowRunId(71L)
        .workflowNodeRunId(82L)
        .reason("rerun failed node")
        .requestedBy("operator-1")
        .resultPolicy("CREATE_NEW_VERSION")
        .configVersionPolicy("USE_ORIGINAL_CONFIG")
        .configVersion(null)
        .traceId("trace-source-node")
        .operationKey(preview.operationKey())
        .previewToken(preview.previewToken())
        .build());
    command = CompensationSubmitCommand.builder()
        .tenantId("t1")
        .compensationType("WORKFLOW_NODE")
        .targetId(82L)
        .jobCode("IMPORT_CUSTOMERS")
        .bizDate(bizDate)
        .sourceWorkflowRunId(71L)
        .sourceWorkflowNodeRunId(82L)
        .replayOperationId(pending.id())
        .resultPolicy("CREATE_NEW_VERSION")
        .configVersionPolicy("USE_ORIGINAL_CONFIG")
        .reason("rerun failed node")
        .operatorId("operator-1")
        .approvalId("APR-NODE-1")
        .traceId("trace-source-node")
        .build();

    service.submit(command);

    ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
    verify(domainEventPublisher).publish(eventCaptor.capture());
    LaunchRequest launch = JsonUtils.fromJson(
        JsonUtils.toJson(eventCaptor.getValue().payload().get("launchRequest")),
        LaunchRequest.class);
    assertThat(launch.bizDate()).isEqualTo(bizDate);
    assertThat(launch.params())
        .containsEntry("sourceWorkflowRunId", 71)
        .containsEntry("sourceWorkflowNodeRunId", 82)
        .containsEntry("sourceJobInstanceId", 93);

    ArgumentCaptor<CompensationCommandEntity> insert =
        ArgumentCaptor.forClass(CompensationCommandEntity.class);
    verify(compensationCommandMapper).insert(insert.capture());
    assertThat(insert.getValue().getCompensationType()).isEqualTo("WORKFLOW_NODE");
    assertThat(insert.getValue().getTargetId()).isEqualTo(82L);
    assertThat(insert.getValue().getSourceWorkflowRunId()).isEqualTo(71L);
    ReplayOperationEntity replayOperation = pendingOperation.get();
    assertThat(replayOperation.operationType()).isEqualTo("WORKFLOW_NODE");
    assertThat(replayOperation.sourceWorkflowRunId()).isEqualTo(71L);
    assertThat(replayOperation.sourceWorkflowNodeRunId()).isEqualTo(82L);
    assertThat(replayOperation.sourceJobInstanceId()).isEqualTo(93L);
    assertThat(replayOperation.requestedBy()).isEqualTo("operator-1");
    verify(replayOperationMapper).startApprovedWorkflowNodeReplay(any());
    assertThat(replayOperation.inFlightCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("来源任务外部副作用结果未知时拒绝作业重跑且不创建 Workflow 请求")
  void shouldRejectJobRerunWhenSourceHasUnknownExternalOutcome() {
    CompensationSubmitCommand cmd = command("t1", "JOB", 100L);
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), any(), anyString()))
        .thenReturn(0);
    JobInstanceEntity sourceInstance = new JobInstanceEntity();
    sourceInstance.setId(100L);
    sourceInstance.setInstanceNo("inst-100");
    sourceInstance.setJobCode("JOB_CODE");
    sourceInstance.setBizDate(LocalDate.now());
    when(jobMappers.jobInstanceMapper.selectById("t1", 100L)).thenReturn(sourceInstance);
    when(jobMappers.jobTaskMapper.existsUnknownExternalOutcomeForJobInstance("t1", 100L))
        .thenReturn(true);
    stubManagedWorkflow("JOB_CODE", 10L);

    assertThatThrownBy(() -> service.submit(cmd))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.task.retry_unknown_outcome_requires_resolution");

    Mockito.verifyNoInteractions(domainEventPublisher);
  }

  @Test
  @DisplayName("指定作业业务日存在未知外部结果时拒绝批量重跑且不创建 Workflow 请求")
  void shouldRejectBatchRerunWhenJobBizDateHasUnknownExternalOutcome() {
    LocalDate bizDate = LocalDate.of(2026, 10, 13);
    CompensationSubmitCommand cmd = CompensationSubmitCommand.builder()
        .tenantId("t1")
        .compensationType("BATCH")
        .jobCode("JOB_CODE")
        .bizDate(bizDate)
        .reason("unknown external outcome must be reconciled first")
        .build();
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), any(), anyString()))
        .thenReturn(0);
    when(jobMappers.jobTaskMapper.existsUnknownExternalOutcomeForJobBizDate(
            "t1", "JOB_CODE", bizDate))
        .thenReturn(true);
    stubManagedWorkflow("JOB_CODE", 10L);

    assertThatThrownBy(() -> service.submit(cmd))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.task.retry_unknown_outcome_requires_resolution");

    Mockito.verifyNoInteractions(domainEventPublisher);
  }

  @Test
  @DisplayName("自助重跑实例号与请求作业或业务日不匹配时拒绝提交")
  void shouldRejectRerunInstanceOutsideRequestedJobAndBizDate() {
    LocalDate requestedBizDate = LocalDate.of(2026, 10, 13);
    CompensationSubmitCommand cmd = CompensationSubmitCommand.builder()
        .tenantId("t1")
        .compensationType("JOB")
        .targetInstanceNo("old-A-20261012")
        .jobCode("JOB_B")
        .bizDate(requestedBizDate)
        .reason("target must match selected job and date")
        .build();
    JobInstanceEntity staleInstance = new JobInstanceEntity();
    staleInstance.setId(100L);
    staleInstance.setInstanceNo("old-A-20261012");
    staleInstance.setJobCode("JOB_A");
    staleInstance.setBizDate(LocalDate.of(2026, 10, 12));
    when(jobMappers.jobInstanceMapper.selectByInstanceNo("t1", "old-A-20261012"))
        .thenReturn(staleInstance);

    assertThatThrownBy(() -> service.submit(cmd)).isInstanceOf(BizException.class);

    Mockito.verifyNoInteractions(domainEventPublisher);
  }

  @Test
  @DisplayName("自助重跑实例业务日不匹配时拒绝执行")
  void shouldRejectRerunInstanceWithDifferentBizDate() {
    LocalDate requestedBizDate = LocalDate.of(2026, 10, 13);
    CompensationSubmitCommand cmd = CompensationSubmitCommand.builder()
        .tenantId("t1")
        .compensationType("JOB")
        .targetInstanceNo("instance-a")
        .jobCode("JOB_A")
        .bizDate(requestedBizDate)
        .build();
    JobInstanceEntity staleInstance = new JobInstanceEntity();
    staleInstance.setId(100L);
    staleInstance.setInstanceNo("instance-a");
    staleInstance.setJobCode("JOB_A");
    staleInstance.setBizDate(LocalDate.of(2026, 10, 12));
    when(jobMappers.jobInstanceMapper.selectByInstanceNo("t1", "instance-a"))
        .thenReturn(staleInstance);

    assertThatThrownBy(() -> service.submit(cmd)).isInstanceOf(BizException.class);

    Mockito.verifyNoInteractions(domainEventPublisher);
  }

  @Test
  @DisplayName("自助重跑实例与请求作业和业务日一致时按该身份启动")
  void shouldRerunMatchingRequestedInstance() {
    LocalDate requestedBizDate = LocalDate.of(2026, 10, 13);
    CompensationSubmitCommand cmd = CompensationSubmitCommand.builder()
        .tenantId("t1")
        .compensationType("JOB")
        .targetInstanceNo("instance-a")
        .jobCode("JOB_A")
        .bizDate(requestedBizDate)
        .build();
    JobInstanceEntity sourceInstance = new JobInstanceEntity();
    sourceInstance.setId(100L);
    sourceInstance.setInstanceNo("instance-a");
    sourceInstance.setJobCode("JOB_A");
    sourceInstance.setBizDate(requestedBizDate);
    sourceInstance.setBatchNo("batch-a");
    sourceInstance.setParamsSnapshot("""
        {"effectiveParams":{"run_mode":"NORMAL","customerFilter":"active"}}
        """);
    when(jobMappers.jobInstanceMapper.selectByInstanceNo("t1", "instance-a"))
        .thenReturn(sourceInstance);
    stubManagedWorkflow("JOB_A", 10L);

    service.submit(cmd);

    ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
    verify(domainEventPublisher).publish(eventCaptor.capture());
    LaunchRequest acceptedLaunch = JsonUtils.fromJson(
        JsonUtils.toJson(eventCaptor.getValue().payload().get("launchRequest")),
        LaunchRequest.class);
    assertThat(acceptedLaunch.jobCode()).isNull();
    assertThat(acceptedLaunch.workflowDefinitionId()).isEqualTo(110L);
    assertThat(acceptedLaunch.bizDate()).isEqualTo(requestedBizDate);
    assertThat(acceptedLaunch.params()).containsEntry("run_mode", RunMode.RERUN.code());
  }

  @Test
  @DisplayName("批量类补偿成功时拉起批量实例并登记触发请求")
  void shouldRerunBatchSuccessfully_BATCH_path() {
    LocalDate bizDate = LocalDate.now();
    CompensationSubmitCommand cmd = new CompensationSubmitCommand(
        "t1",
        "BATCH",
        null,
        null,
        "BATCH_JOB_CODE",
        bizDate,
        "batch-200",
        null,
        null,
        "rerun batch",
        "op-001",
        null,
        null,
        null);
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), any(), anyString()))
        .thenReturn(0);
    stubManagedWorkflow("BATCH_JOB_CODE", 20L);
    when(jobMappers.jobInstanceMapper.selectByInstanceNo("t1", "inst-200")).thenReturn(null);

    String commandNo = service.submit(cmd);

    assertThat(commandNo).isNotBlank();
    verify(domainEventPublisher).publish(any(DomainEvent.class));
    verify(jobMappers.triggerRequestMapper).insert(Mockito.any());
  }

  @Test
  @DisplayName("Workflow 根标识不能冒充 Job 实例号，子 Job 未创建时关联必须留空")
  void shouldKeepJobAssociationUnbound_whenWorkflowRootHasNoChildYet() {
    var source = new JobInstanceEntity();
    source.setId(100L);
    source.setTenantId("t1");
    source.setJobCode("JOB_CODE");
    source.setInstanceNo("source-100");
    source.setBizDate(LocalDate.of(2026, 10, 1));
    when(jobMappers.jobInstanceMapper.selectById("t1", 100L)).thenReturn(source);
    stubManagedWorkflow("JOB_CODE", 10L);
    var collision = new JobInstanceEntity();
    collision.setId(999L);
    collision.setInstanceNo("42");
    when(jobMappers.jobInstanceMapper.selectByInstanceNo("t1", "42")).thenReturn(collision);
    service.submit(command("t1", "JOB", 100L));
    var update = ArgumentCaptor.forClass(UpdateCompensationStatusParam.class);
    verify(compensationCommandMapper).updateStatus(update.capture());
    assertThat(update.getValue().getRelatedJobInstanceId()).isNull();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void stubManagedWorkflow(String jobCode, Long jobId) {
    var accepted = new AtomicReference<TriggerRequestEntity>();
    Mockito.doAnswer(call -> {
          TriggerRequestEntity row = call.getArgument(0);
          row.setId(1L);
          accepted.set(row);
          return 1;
        })
        .when(jobMappers.triggerRequestMapper)
        .insert(any(TriggerRequestEntity.class));
    when(jobMappers.triggerRequestMapper.selectByTenantAndRequestId(anyString(), anyString()))
        .thenAnswer(call -> accepted.get());

    when(configCacheService.findEnabledJobDefinition("t1", jobCode))
        .thenReturn(JobDefinitionEntity.builder()
            .id(jobId)
            .tenantId("t1")
            .jobCode(jobCode)
            .jobType("GENERAL")
            .version(1)
            .enabled(true)
            .build());
    when(configCacheService.findEnabledSingleJobWorkflowDefinition("t1", jobId))
        .thenReturn(new WorkflowDefinitionEntity(
            jobId + 100,
            "t1",
            "WF_" + jobCode,
            "WF " + jobCode,
            "SINGLE_JOB",
            1,
            true,
            jobId,
            null));
  }

  private static CompensationSubmitCommand command(
      String tenantId, String compensationType, Long targetId) {
    return new CompensationSubmitCommand(
        tenantId,
        compensationType,
        targetId,
        null,
        null,
        null,
        null,
        null,
        null,
        "test reason",
        "op-001",
        null,
        null,
        null);
  }
}
