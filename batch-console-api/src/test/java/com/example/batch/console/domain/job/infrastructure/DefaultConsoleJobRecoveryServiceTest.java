package com.example.batch.console.domain.job.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.governance.web.request.DeadLetterReplayRequest;
import com.example.batch.console.domain.job.web.request.CompensateRequest;
import com.example.batch.console.domain.job.web.request.CompensationCommandRequest;
import com.example.batch.console.domain.job.web.request.PartitionReplayRequest;
import com.example.batch.console.domain.job.web.request.RerunRequest;
import com.example.batch.console.domain.job.web.request.TaskReplayRequest;
import com.example.batch.console.domain.ops.infrastructure.ConsoleJobOpsSupport;
import com.example.batch.console.domain.ops.infrastructure.ConsoleJobOpsSupport.ApprovalSubmitContext;
import com.example.batch.console.domain.ops.infrastructure.ConsoleJobOpsSupport.CompensationPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultConsoleJobRecoveryServiceTest {

  private static final String TENANT = "t1";
  private static final String IDEMPOTENCY = "idem-1";

  @Mock private ConsoleJobOpsSupport ops;
  @InjectMocks private DefaultConsoleJobRecoveryService service;

  @BeforeEach
  void setUp() {
    when(ops.resolveTenant(any())).thenReturn(TENANT);
  }

  // ── compensation: approval-first path ───────────────────────────────────

  @Test
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

  // ── compensation: with approval id ──────────────────────────────────────

  @Test
  void shouldSubmitCompensation_whenCompensationHasApprovalId() {
    CompensationCommandRequest req = compensationRequest("APR-001");
    when(ops.hasText("APR-001")).thenReturn(true);
    when(ops.parseOptionalBizDate("2026-04-10")).thenReturn(null);
    when(ops.submitCompensation(any(CompensationPayload.class), eq(IDEMPOTENCY)))
        .thenReturn("CMD-100");

    String result = service.compensation(req, IDEMPOTENCY);

    assertThat(result).isEqualTo("CMD-100");
    verify(ops).requireApprovedApproval(TENANT, "APR-001");
    verify(ops).publishRefresh(TENANT);
  }

  // ── compensate (no approval gating) ─────────────────────────────────────

  @Test
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
  void shouldReplayDeadLetter_whenApprovalIdProvided() {
    DeadLetterReplayRequest req = deadLetterRequest("APR-DLQ");
    when(ops.hasText("APR-DLQ")).thenReturn(true);
    when(ops.submitCompensation(any(CompensationPayload.class), anyString())).thenReturn("CMD-DLQ");

    String result = service.replayDeadLetter(req, IDEMPOTENCY);

    assertThat(result).isEqualTo("CMD-DLQ");
    verify(ops).requireApprovedApproval(TENANT, "APR-DLQ");
    ArgumentCaptor<CompensationPayload> captor = ArgumentCaptor.forClass(CompensationPayload.class);
    verify(ops).submitCompensation(captor.capture(), anyString());
    assertThat(captor.getValue().getCompensationType()).isEqualTo("DLQ");
    verify(ops).publishRefresh(TENANT);
  }

  // ── replayTask ──────────────────────────────────────────────────────────

  @Test
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
  void shouldReplayTask_whenApprovalIdProvided() {
    TaskReplayRequest req = taskReplayRequest("APR-TASK");
    when(ops.hasText("APR-TASK")).thenReturn(true);
    when(ops.triggerRecovery(eq(TENANT), anyString(), eq(55L), eq(IDEMPOTENCY))).thenReturn("OP-1");

    String result = service.replayTask(req, IDEMPOTENCY);

    assertThat(result).isEqualTo("OP-1");
    verify(ops).requireApprovedApproval(TENANT, "APR-TASK");
    verify(ops).publishRefresh(TENANT);
  }

  // ── replayPartition ─────────────────────────────────────────────────────

  @Test
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
  void shouldReplayPartition_whenApprovalIdProvided() {
    PartitionReplayRequest req = partitionReplayRequest("APR-PART");
    when(ops.hasText("APR-PART")).thenReturn(true);
    when(ops.triggerRecovery(eq(TENANT), anyString(), eq(77L), eq(IDEMPOTENCY))).thenReturn("OP-2");

    String result = service.replayPartition(req, IDEMPOTENCY);

    assertThat(result).isEqualTo("OP-2");
    verify(ops).requireApprovedApproval(TENANT, "APR-PART");
    verify(ops).publishRefresh(TENANT);
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
