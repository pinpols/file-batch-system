package io.github.pinpols.batch.console.domain.job.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.application.ops.ConsoleJobOperationsPort;
import io.github.pinpols.batch.console.domain.job.application.contract.request.CompensateRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.WorkflowRunReplayRequest;
import io.github.pinpols.batch.console.shared.command.ApprovalSubmitContext;
import io.github.pinpols.batch.console.shared.command.CompensationCommandRequest;
import io.github.pinpols.batch.console.shared.command.CompensationPayload;
import io.github.pinpols.batch.console.shared.command.DeadLetterReplayRequest;
import io.github.pinpols.batch.console.shared.command.PartitionReplayRequest;
import io.github.pinpols.batch.console.shared.command.RerunRequest;
import io.github.pinpols.batch.console.shared.command.TaskReplayRequest;
import io.github.pinpols.batch.common.utils.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("作业恢复服务: 审批前置分流, 补偿类型默认值与重跑版本策略校验")
class DefaultConsoleJobRecoveryServiceTest {

  private static final String TENANT = "t1";
  private static final String IDEMPOTENCY = "idem-1";

  @Mock
  private ConsoleJobOperationsPort ops;

  @InjectMocks
  private DefaultConsoleJobRecoveryService service;

  @BeforeEach
  void setUp() {
    when(ops.resolveTenant(any())).thenReturn(TENANT);
  }

  // ── compensation: approval-first path ───────────────────────────────────

  @Test
  @DisplayName("补偿未带审批单号时转为提交审批, 审批上下文按目标实例与幂等键组装")
  void shouldSubmitApproval_whenCompensationHasNoApprovalId() {
    CompensationCommandRequest req = compensationRequest(null);
    when(ops.hasText(null)).thenReturn(false);
    when(ops.submitApproval(any(ApprovalSubmitContext.class))).thenReturn("APR-001");

    String result = service.compensation(req, IDEMPOTENCY);

    assertThat(result).isEqualTo("APR-001");
    ArgumentCaptor<ApprovalSubmitContext> captor =
        ArgumentCaptor.forClass(ApprovalSubmitContext.class);
    verify(ops).submitApproval(captor.capture());
    ApprovalSubmitContext ctx = captor.getValue();
    assertThat(ctx.approvalType()).isEqualTo("COMPENSATION");
    assertThat(ctx.actionType()).isEqualTo("COMPENSATION");
    assertThat(ctx.targetType()).isEqualTo("JOB");
    assertThat(ctx.targetId()).isEqualTo("123");
    assertThat(ctx.idempotencyKey()).isEqualTo(IDEMPOTENCY);
    verify(ops).publishRefresh(TENANT);
    verify(ops, never()).submitCompensation(any(), anyString());
  }

  @Test
  @DisplayName("失败 Workflow 节点重放审批保留节点目标类型")
  void shouldSubmitWorkflowNodeApprovalWithExactTarget() {
    CompensationCommandRequest req = compensationRequest(null);
    req.setCompensationType("WORKFLOW_NODE");
    req.setSourceWorkflowRunId(71L);
    req.setSourceWorkflowNodeRunId(82L);
    req.setTargetId(82L);
    req.setOperationKey("44a76b9e-3cc0-46f0-9ac4-16ca44f80684");
    req.setPreviewToken("one-use-token");
    req.setResultPolicy("CREATE_NEW_VERSION");
    req.setConfigVersionPolicy("USE_ORIGINAL_CONFIG");
    when(ops.hasText(null)).thenReturn(false);
    when(ops.hasText(req.getOperationKey())).thenReturn(true);
    when(ops.hasText(req.getPreviewToken())).thenReturn(true);
    when(ops.createPendingWorkflowNodeReplay(req)).thenReturn(901L);
    when(ops.submitApproval(any(ApprovalSubmitContext.class))).thenReturn("APR-NODE-2");

    String result = service.compensation(req, IDEMPOTENCY);

    assertThat(result).isEqualTo("APR-NODE-2");
    ArgumentCaptor<ApprovalSubmitContext> approvalCaptor =
        ArgumentCaptor.forClass(ApprovalSubmitContext.class);
    verify(ops).submitApproval(approvalCaptor.capture());
    ApprovalSubmitContext approval = approvalCaptor.getValue();
    assertThat(approval.targetType()).isEqualTo("WORKFLOW_NODE");
    assertThat(approval.targetId()).isEqualTo("82");
    assertThat(approval.payload()).isSameAs(req);
    assertThat(req.getReplayOperationId()).isEqualTo(901L);
    assertThat(approval.idempotencyKey()).isEqualTo(req.getOperationKey());
    verify(ops).createPendingWorkflowNodeReplay(req);
    verify(ops, never()).submitCompensation(any(), anyString());
  }

  @Test
  @DisplayName("整 Workflow Replay 创建持久 operation 并登记认证审批动作")
  void shouldSubmitWholeWorkflowReplayApproval() {
    WorkflowRunReplayRequest request = new WorkflowRunReplayRequest();
    request.setTenantId(TENANT);
    request.setWorkflowRunId(71L);
    request.setReason("replay whole workflow");
    request.setOperationKey("44a76b9e-3cc0-46f0-9ac4-16ca44f80684");
    request.setPreviewToken("preview-token");
    when(ops.hasText(request.getOperationKey())).thenReturn(true);
    when(ops.createPendingWorkflowRunReplay(request)).thenReturn(901L);
    when(ops.submitApproval(any(ApprovalSubmitContext.class))).thenReturn("APR-WORKFLOW-1");

    var result = service.submitWorkflowRunReplay(request, request.getOperationKey());

    assertThat(result.replayOperationId()).isEqualTo(901L);
    assertThat(result.approvalNo()).isEqualTo("APR-WORKFLOW-1");
    ArgumentCaptor<ApprovalSubmitContext> approvalCaptor =
        ArgumentCaptor.forClass(ApprovalSubmitContext.class);
    verify(ops).submitApproval(approvalCaptor.capture());
    ApprovalSubmitContext approval = approvalCaptor.getValue();
    assertThat(approval.approvalType()).isEqualTo("COMPENSATION");
    assertThat(approval.actionType()).isEqualTo("WORKFLOW_RUN_REPLAY");
    assertThat(approval.targetType()).isEqualTo("WORKFLOW_RUN");
    assertThat(approval.targetId()).isEqualTo("71");
    assertThat(JsonUtils.toJson(approval.payload()))
        .contains("\"tenantId\":\"t1\"", "\"replayOperationId\":901");
    assertThat(approval.idempotencyKey()).isEqualTo(request.getOperationKey());
    verify(ops).publishRefresh(TENANT);
  }

  // ── compensation: with approval id ──────────────────────────────────────

  @Test
  @DisplayName("补偿带审批单号且审批通过时直接下发补偿指令并刷新视图")
  void shouldSubmitCompensation_whenCompensationHasApprovalId() {
    CompensationCommandRequest req = compensationRequest("APR-001");
    when(ops.hasText("APR-001")).thenReturn(true);
    when(ops.parseOptionalBizDate("2026-04-10")).thenReturn(null);
    when(ops.submitCompensation(any(CompensationPayload.class), eq(IDEMPOTENCY)))
        .thenReturn("CMD-100");

    String result = service.compensation(req, IDEMPOTENCY);

    assertThat(result).isEqualTo("CMD-100");
    verify(ops).requireApprovedApproval(TENANT, "APR-001", "COMPENSATION", req);
    verify(ops).publishRefresh(TENANT);
  }

  // ── compensate (no approval gating) ─────────────────────────────────────

  @Test
  @DisplayName("直接补偿时采用请求指定的补偿类型下发指令")
  void shouldCompensateDirectly_withProvidedType() {
    CompensateRequest req = compensateRequest("WORKFLOW");
    when(ops.parseOptionalBizDate(any())).thenReturn(null);
    when(ops.submitCompensation(any(CompensationPayload.class), eq(IDEMPOTENCY)))
        .thenReturn("CMD-200");

    String result = service.compensate(req, IDEMPOTENCY);

    assertThat(result).isEqualTo("CMD-200");
    ArgumentCaptor<CompensationPayload> captor = ArgumentCaptor.forClass(CompensationPayload.class);
    verify(ops).submitCompensation(captor.capture(), eq(IDEMPOTENCY));
    assertThat(captor.getValue().getCompensationType()).isEqualTo("WORKFLOW");
    verify(ops).publishRefresh(TENANT);
  }

  @Test
  @DisplayName("失败 Workflow 节点重放必须带已批准的审批单并保留精确来源身份")
  void shouldForwardExactWorkflowNodeReplaySource() {
    CompensateRequest req = compensateRequest("WORKFLOW_NODE");
    req.setTargetId(82L);
    req.setSourceWorkflowRunId(71L);
    req.setSourceWorkflowNodeRunId(82L);
    req.setApprovalId("APR-NODE-1");
    req.setReplayOperationId(901L);
    req.setResultPolicy("CREATE_NEW_VERSION");
    req.setConfigVersionPolicy("USE_ORIGINAL_CONFIG");
    req.setConfigVersion(7);
    when(ops.hasText("APR-NODE-1")).thenReturn(true);
    when(ops.parseOptionalBizDate(any())).thenReturn(null);
    when(ops.submitCompensation(any(CompensationPayload.class), eq(IDEMPOTENCY)))
        .thenReturn("CMD-WORKFLOW-NODE");

    service.compensate(req, IDEMPOTENCY);

    ArgumentCaptor<CompensationPayload> captor = ArgumentCaptor.forClass(CompensationPayload.class);
    verify(ops).requireApprovedApproval(TENANT, "APR-NODE-1", "COMPENSATION", req);
    verify(ops).submitCompensation(captor.capture(), eq(IDEMPOTENCY));
    assertThat(captor.getValue().getCompensationType()).isEqualTo("WORKFLOW_NODE");
    assertThat(captor.getValue().getTargetId()).isEqualTo(82L);
    assertThat(captor.getValue().getSourceWorkflowRunId()).isEqualTo(71L);
    assertThat(captor.getValue().getSourceWorkflowNodeRunId()).isEqualTo(82L);
    assertThat(captor.getValue().getResultPolicy()).isEqualTo("CREATE_NEW_VERSION");
    assertThat(captor.getValue().getConfigVersionPolicy()).isEqualTo("USE_ORIGINAL_CONFIG");
    assertThat(captor.getValue().getConfigVersion()).isEqualTo(7);
  }

  @Test
  @DisplayName("失败 Workflow 节点重放拒绝绕过审批直接执行")
  void shouldRejectDirectWorkflowNodeCompensationWithoutApproval() {
    CompensateRequest req = compensateRequest("WORKFLOW_NODE");
    req.setTargetId(82L);
    req.setSourceWorkflowRunId(71L);
    req.setSourceWorkflowNodeRunId(82L);
    when(ops.hasText(null)).thenReturn(false);

    assertThatThrownBy(() -> service.compensate(req, IDEMPOTENCY))
        .isInstanceOf(BizException.class)
        .extracting(exception -> ((BizException) exception).getCode())
        .isEqualTo(ResultCode.STATE_CONFLICT);

    verify(ops, never()).submitCompensation(any(), anyString());
  }

  @Test
  @DisplayName("补偿类型为空白串时回退为作业类型下发指令")
  void shouldCompensateDirectly_withDefaultJobType_whenTypeBlank() {
    CompensateRequest req = compensateRequest("");
    when(ops.parseOptionalBizDate(any())).thenReturn(null);
    when(ops.submitCompensation(any(CompensationPayload.class), anyString())).thenReturn("CMD-3");

    service.compensate(req, IDEMPOTENCY);

    ArgumentCaptor<CompensationPayload> captor = ArgumentCaptor.forClass(CompensationPayload.class);
    verify(ops).submitCompensation(captor.capture(), anyString());
    assertThat(captor.getValue().getCompensationType()).isEqualTo("JOB");
  }

  @Test
  @DisplayName("补偿类型缺省时回退为作业类型下发指令")
  void shouldCompensateDirectly_withDefaultJobType_whenTypeNull() {
    CompensateRequest req = compensateRequest(null);
    when(ops.parseOptionalBizDate(any())).thenReturn(null);
    when(ops.submitCompensation(any(CompensationPayload.class), anyString())).thenReturn("CMD-4");

    service.compensate(req, IDEMPOTENCY);

    ArgumentCaptor<CompensationPayload> captor = ArgumentCaptor.forClass(CompensationPayload.class);
    verify(ops).submitCompensation(captor.capture(), anyString());
    assertThat(captor.getValue().getCompensationType()).isEqualTo("JOB");
  }

  // ── rerun ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("重跑指定目标作业编号时按作业粒度下发补偿指令")
  void shouldRerun_asJob_whenTargetIdPresent() {
    RerunRequest req = rerunRequest();
    req.setTargetId(99L);
    when(ops.parseOptionalBizDate(any())).thenReturn(null);
    when(ops.submitCompensation(any(CompensationPayload.class), anyString())).thenReturn("CMD-5");

    service.rerun(req, IDEMPOTENCY);

    ArgumentCaptor<CompensationPayload> captor = ArgumentCaptor.forClass(CompensationPayload.class);
    verify(ops).submitCompensation(captor.capture(), anyString());
    assertThat(captor.getValue().getCompensationType()).isEqualTo("JOB");
  }

  @Test
  @DisplayName("重跑指定目标实例编号时按作业粒度下发补偿指令")
  void shouldRerun_asJob_whenTargetInstanceNoPresent() {
    RerunRequest req = rerunRequest();
    req.setTargetInstanceNo("INST-1");
    when(ops.parseOptionalBizDate(any())).thenReturn(null);
    when(ops.submitCompensation(any(CompensationPayload.class), anyString())).thenReturn("CMD-6");

    service.rerun(req, IDEMPOTENCY);

    ArgumentCaptor<CompensationPayload> captor = ArgumentCaptor.forClass(CompensationPayload.class);
    verify(ops).submitCompensation(captor.capture(), anyString());
    assertThat(captor.getValue().getCompensationType()).isEqualTo("JOB");
  }

  @Test
  @DisplayName("重跑未指定目标时按批次粒度下发补偿指令")
  void shouldRerun_asBatch_whenNoTarget() {
    RerunRequest req = rerunRequest();
    when(ops.parseOptionalBizDate(any())).thenReturn(null);
    when(ops.submitCompensation(any(CompensationPayload.class), anyString())).thenReturn("CMD-7");

    service.rerun(req, IDEMPOTENCY);

    ArgumentCaptor<CompensationPayload> captor = ArgumentCaptor.forClass(CompensationPayload.class);
    verify(ops).submitCompensation(captor.capture(), anyString());
    assertThat(captor.getValue().getCompensationType()).isEqualTo("BATCH");
  }

  @Test
  @DisplayName("指定配置版本策略却缺少版本号时返回参数错误且不下发指令")
  void shouldThrowBizException_whenRerunUseSpecifiedVersionMissingConfigVersion() {
    RerunRequest req = rerunRequest();
    req.setConfigVersionPolicy("USE_SPECIFIED_VERSION");
    req.setConfigVersion(null);

    assertThatThrownBy(() -> service.rerun(req, IDEMPOTENCY))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(ResultCode.INVALID_ARGUMENT);
    verify(ops, never()).submitCompensation(any(), anyString());
  }

  @Test
  @DisplayName("指定配置版本策略且版本号齐备时正常下发并返回指令编号")
  void shouldRerun_whenUseSpecifiedVersionWithConfigVersion() {
    RerunRequest req = rerunRequest();
    req.setConfigVersionPolicy("USE_SPECIFIED_VERSION");
    req.setConfigVersion(7);
    when(ops.parseOptionalBizDate(any())).thenReturn(null);
    when(ops.submitCompensation(any(CompensationPayload.class), anyString())).thenReturn("CMD-8");

    String result = service.rerun(req, IDEMPOTENCY);
    assertThat(result).isEqualTo("CMD-8");
  }

  // ── replayDeadLetter ────────────────────────────────────────────────────

  @Test
  @DisplayName("死信重放未带审批单号时转为提交审批, 审批类型与目标类型按死信场景组装")
  void shouldSubmitApproval_whenDeadLetterReplayHasNoApprovalId() {
    DeadLetterReplayRequest req = deadLetterRequest(null);
    when(ops.hasText(null)).thenReturn(false);
    when(ops.submitApproval(any())).thenReturn("APR-DLQ");

    String result = service.replayDeadLetter(req, IDEMPOTENCY);

    assertThat(result).isEqualTo("APR-DLQ");
    ArgumentCaptor<ApprovalSubmitContext> captor =
        ArgumentCaptor.forClass(ApprovalSubmitContext.class);
    verify(ops).submitApproval(captor.capture());
    assertThat(captor.getValue().approvalType()).isEqualTo("DLQ_REPLAY");
    assertThat(captor.getValue().targetType()).isEqualTo("DLQ");
  }

  @Test
  @DisplayName("死信重放带审批单号且审批通过时按死信补偿类型下发指令")
  void shouldReplayDeadLetter_whenApprovalIdProvided() {
    DeadLetterReplayRequest req = deadLetterRequest("APR-DLQ");
    when(ops.hasText("APR-DLQ")).thenReturn(true);
    when(ops.submitCompensation(any(CompensationPayload.class), anyString())).thenReturn("CMD-DLQ");

    String result = service.replayDeadLetter(req, IDEMPOTENCY);

    assertThat(result).isEqualTo("CMD-DLQ");
    verify(ops).requireApprovedApproval(TENANT, "APR-DLQ", "DLQ_REPLAY", req);
    ArgumentCaptor<CompensationPayload> captor = ArgumentCaptor.forClass(CompensationPayload.class);
    verify(ops).submitCompensation(captor.capture(), anyString());
    assertThat(captor.getValue().getCompensationType()).isEqualTo("DLQ");
    verify(ops).publishRefresh(TENANT);
  }

  @Test
  @DisplayName("死信重放审批通过时将外部动作核实声明和证据引用传给编排器")
  void shouldPassExternalOutcomeEvidence_whenReplayingApprovedDeadLetter() {
    DeadLetterReplayRequest req = deadLetterRequest("APR-DLQ");
    req.setExternalActionVerifiedNotExecuted(true);
    req.setExternalOutcomeEvidenceReference("incident-123/evidence-1");
    when(ops.hasText("APR-DLQ")).thenReturn(true);
    when(ops.submitCompensation(any(CompensationPayload.class), anyString())).thenReturn("CMD-DLQ");

    service.replayDeadLetter(req, IDEMPOTENCY);

    ArgumentCaptor<CompensationPayload> captor = ArgumentCaptor.forClass(CompensationPayload.class);
    verify(ops).submitCompensation(captor.capture(), anyString());
    assertThat(captor.getValue().getExternalActionVerifiedNotExecuted()).isTrue();
    assertThat(captor.getValue().getExternalOutcomeEvidenceReference())
        .isEqualTo("incident-123/evidence-1");
  }

  // ── replayTask ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("任务重放未带审批单号时转为提交审批, 动作类型为重试且目标为作业任务")
  void shouldSubmitApproval_whenTaskReplayHasNoApprovalId() {
    TaskReplayRequest req = taskReplayRequest(null);
    when(ops.hasText(null)).thenReturn(false);
    when(ops.submitApproval(any())).thenReturn("APR-TASK");

    String result = service.replayTask(req, IDEMPOTENCY);

    assertThat(result).isEqualTo("APR-TASK");
    ArgumentCaptor<ApprovalSubmitContext> captor =
        ArgumentCaptor.forClass(ApprovalSubmitContext.class);
    verify(ops).submitApproval(captor.capture());
    assertThat(captor.getValue().actionType()).isEqualTo("RETRY");
    assertThat(captor.getValue().targetType()).isEqualTo("JOB_TASK");
  }

  @Test
  @DisplayName("任务重放带审批单号且审批通过时触发恢复并返回操作编号")
  void shouldReplayTask_whenApprovalIdProvided() {
    TaskReplayRequest req = taskReplayRequest("APR-TASK");
    when(ops.hasText("APR-TASK")).thenReturn(true);
    when(ops.triggerRecovery(eq(TENANT), anyString(), eq(55L), eq(IDEMPOTENCY))).thenReturn("OP-1");

    String result = service.replayTask(req, IDEMPOTENCY);

    assertThat(result).isEqualTo("OP-1");
    verify(ops).requireApprovedApproval(TENANT, "APR-TASK", "RETRY", req);
    verify(ops).publishRefresh(TENANT);
  }

  // ── replayPartition ─────────────────────────────────────────────────────

  @Test
  @DisplayName("分区重放未带审批单号时转为提交审批, 目标类型为作业分区")
  void shouldSubmitApproval_whenPartitionReplayHasNoApprovalId() {
    PartitionReplayRequest req = partitionReplayRequest(null);
    when(ops.hasText(null)).thenReturn(false);
    when(ops.submitApproval(any())).thenReturn("APR-PART");

    String result = service.replayPartition(req, IDEMPOTENCY);

    assertThat(result).isEqualTo("APR-PART");
    ArgumentCaptor<ApprovalSubmitContext> captor =
        ArgumentCaptor.forClass(ApprovalSubmitContext.class);
    verify(ops).submitApproval(captor.capture());
    assertThat(captor.getValue().targetType()).isEqualTo("JOB_PARTITION");
  }

  @Test
  @DisplayName("分区重放带审批单号且审批通过时触发恢复并返回操作编号")
  void shouldReplayPartition_whenApprovalIdProvided() {
    PartitionReplayRequest req = partitionReplayRequest("APR-PART");
    when(ops.hasText("APR-PART")).thenReturn(true);
    when(ops.triggerRecovery(eq(TENANT), anyString(), eq(77L), eq(IDEMPOTENCY))).thenReturn("OP-2");

    String result = service.replayPartition(req, IDEMPOTENCY);

    assertThat(result).isEqualTo("OP-2");
    verify(ops).requireApprovedApproval(TENANT, "APR-PART", "RETRY", req);
    verify(ops).publishRefresh(TENANT);
  }

  @Test
  @DisplayName("带审批的重跑须先绑定批准动作和完整请求，再提交补偿")
  void shouldBindRerunPayload_whenApprovalIdWasProvided() {
    RerunRequest request = new RerunRequest();
    request.setTenantId(TENANT);
    request.setJobCode("JOB-1");
    request.setBizDate("2026-04-10");
    request.setTargetId(123L);
    request.setApprovalId("APR-RERUN");
    when(ops.hasText("APR-RERUN")).thenReturn(true);
    when(ops.submitCompensation(any(), eq(IDEMPOTENCY))).thenReturn("CMD-RERUN");

    assertThat(service.rerun(request, IDEMPOTENCY)).isEqualTo("CMD-RERUN");
    verify(ops).requireApprovedApproval(TENANT, "APR-RERUN", "RERUN", request);
  }

  @Test
  @DisplayName("直接补偿入口附带审批号时也须复核批准载荷")
  void shouldBindCompensatePayload_whenApprovalIdWasProvided() {
    CompensateRequest request = compensateRequest("JOB");
    request.setApprovalId("APR-COMPENSATE");
    when(ops.hasText("APR-COMPENSATE")).thenReturn(true);
    when(ops.submitCompensation(any(), eq(IDEMPOTENCY))).thenReturn("CMD-COMPENSATE");

    assertThat(service.compensate(request, IDEMPOTENCY)).isEqualTo("CMD-COMPENSATE");
    verify(ops).requireApprovedApproval(TENANT, "APR-COMPENSATE", "COMPENSATION", request);
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static CompensationCommandRequest compensationRequest(String approvalId) {
    CompensationCommandRequest req = new CompensationCommandRequest();
    req.setTenantId(TENANT);
    req.setCompensationType("JOB");
    req.setTargetId(123L);
    req.setTargetInstanceNo("INST-1");
    req.setJobCode("JOB-1");
    req.setBizDate("2026-04-10");
    req.setBatchNo("B-1");
    req.setRelatedFileId(1L);
    req.setChannelCode("CH-1");
    req.setReason("reason");
    req.setOperatorId("op");
    req.setApprovalId(approvalId);
    req.setStrategy("FULL");
    return req;
  }

  private static CompensateRequest compensateRequest(String type) {
    CompensateRequest req = new CompensateRequest();
    req.setTenantId(TENANT);
    req.setJobCode("JOB-1");
    req.setBizDate("2026-04-10");
    req.setCompensationType(type);
    req.setTargetId(1L);
    req.setTargetInstanceNo("INST-1");
    req.setBatchNo("B-1");
    req.setRelatedFileId(1L);
    req.setChannelCode("CH-1");
    req.setReason("reason");
    req.setOperatorId("op");
    req.setApprovalId(null);
    req.setStrategy("FULL");
    return req;
  }

  private static RerunRequest rerunRequest() {
    RerunRequest req = new RerunRequest();
    req.setTenantId(TENANT);
    req.setJobCode("JOB-1");
    req.setBizDate("2026-04-10");
    req.setBatchNo("B-1");
    req.setRelatedFileId(1L);
    req.setReason("reason");
    req.setOperatorId("op");
    req.setStrategy("FULL");
    req.setResultPolicy("CREATE_NEW_VERSION");
    req.setConfigVersionPolicy("USE_ORIGINAL_CONFIG");
    return req;
  }

  private static DeadLetterReplayRequest deadLetterRequest(String approvalId) {
    DeadLetterReplayRequest req = new DeadLetterReplayRequest();
    req.setTenantId(TENANT);
    req.setDeadLetterId(42L);
    req.setReason("retry");
    req.setOperatorId("op");
    req.setApprovalId(approvalId);
    req.setStrategy("FULL");
    return req;
  }

  private static TaskReplayRequest taskReplayRequest(String approvalId) {
    TaskReplayRequest req = new TaskReplayRequest();
    req.setTenantId(TENANT);
    req.setTaskId(55L);
    req.setReason("retry");
    req.setOperatorId("op");
    req.setApprovalId(approvalId);
    req.setStrategy("FULL");
    return req;
  }

  private static PartitionReplayRequest partitionReplayRequest(String approvalId) {
    PartitionReplayRequest req = new PartitionReplayRequest();
    req.setTenantId(TENANT);
    req.setPartitionId(77L);
    req.setReason("retry");
    req.setOperatorId("op");
    req.setApprovalId(approvalId);
    req.setStrategy("FULL");
    return req;
  }
}
