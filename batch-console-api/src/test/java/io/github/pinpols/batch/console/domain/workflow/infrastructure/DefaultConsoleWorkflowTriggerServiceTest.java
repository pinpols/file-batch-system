package io.github.pinpols.batch.console.domain.workflow.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.ScheduledOccurrenceCatchUpResponse;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.application.ops.ConsoleJobOperationsPort;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDefinitionService;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDefinitionService.DagValidationResult;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.ScheduledOccurrenceCatchUpRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowTriggerRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.WorkflowDefinitionDetailResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.WorkflowTriggerStatusResponse;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowTriggerReceiptMapper;
import io.github.pinpols.batch.console.shared.command.ApprovalSubmitContext;
import io.github.pinpols.batch.console.shared.command.ConsoleScheduledOccurrenceCatchUpCommand;
import io.github.pinpols.batch.console.shared.command.ConsoleWorkflowLaunchCommand;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("Console Workflow 触发与原 occurrence 补跑")
@ExtendWith(MockitoExtension.class)
class DefaultConsoleWorkflowTriggerServiceTest {

  @Mock
  private ConsoleJobOperationsPort operations;

  @Mock
  private WorkflowDefinitionService workflows;

  @Mock
  private WorkflowTriggerReceiptMapper receipts;

  private DefaultConsoleWorkflowTriggerService service;

  @BeforeEach
  void setUp() {
    service = new DefaultConsoleWorkflowTriggerService(operations, workflows, receipts);
  }

  @DisplayName("触发传递 Workflow 目标并刷新运行视图")
  @Test
  void shouldTriggerDelegatesWorkflowTargetAndRefreshesRunViews() {
    when(operations.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(operations.parsePayload(null)).thenReturn(Map.of());
    when(operations.resolveTriggerType(null, TriggerType.MANUAL)).thenReturn(TriggerType.MANUAL);
    when(workflows.getById(23L, "tenant-a")).thenReturn(enabledWorkflow());
    when(workflows.validate(23L, "tenant-a"))
        .thenReturn(new DagValidationResult(true, List.of(), List.of()));
    when(operations.parseBizDate("2026-10-14")).thenReturn(LocalDate.of(2026, 10, 14));
    when(operations.delegateWorkflowLaunch(any())).thenReturn("801");

    String result = service.trigger(
        23L, new WorkflowTriggerRequest("tenant-a", "2026-10-14", null, null), "wf-run-key");

    assertThat(result).isEqualTo("801");
    ArgumentCaptor<ConsoleWorkflowLaunchCommand> command =
        ArgumentCaptor.forClass(ConsoleWorkflowLaunchCommand.class);
    verify(operations).delegateWorkflowLaunch(command.capture());
    assertThat(command.getValue().tenantId()).isEqualTo("tenant-a");
    assertThat(command.getValue().workflowDefinitionId()).isEqualTo(23L);
    assertThat(command.getValue().bizDate()).isEqualTo(LocalDate.of(2026, 10, 14));
    assertThat(command.getValue().triggerType()).isEqualTo(TriggerType.MANUAL);
    assertThat(command.getValue().idempotencyKey()).isEqualTo("wf-run-key");
    verify(operations).publishRefresh("tenant-a");
  }

  @DisplayName("完整停用的 Workflow 拒绝普通触发")
  @Test
  void shouldTriggerRejectsDisabledWorkflowBeforeDelegating() {
    when(operations.resolveTenant("tenant-a")).thenReturn("tenant-a");
    WorkflowDefinitionDetailResponse enabled = enabledWorkflow();
    var disabled = new WorkflowDefinitionDetailResponse(
        enabled.id(),
        enabled.tenantId(),
        enabled.workflowCode(),
        enabled.workflowName(),
        enabled.workflowType(),
        enabled.singleJobDefinitionId(),
        enabled.scheduleType(),
        enabled.scheduleExpr(),
        enabled.timezone(),
        enabled.triggerMode(),
        enabled.calendarCode(),
        enabled.dependsOnJobCode(),
        enabled.version(),
        false,
        enabled.description(),
        enabled.createdAt(),
        enabled.updatedAt(),
        enabled.nodes(),
        enabled.edges());
    when(workflows.getById(23L, "tenant-a")).thenReturn(disabled);

    assertThatThrownBy(() -> service.trigger(
            23L, new WorkflowTriggerRequest("tenant-a", "2026-10-14", null, null), "wf-run-key"))
        .isInstanceOf(BizException.class)
        .hasMessage("error.workflow.definition_disabled");
    verify(operations, never()).delegateWorkflowLaunch(any());
  }

  @DisplayName("补跑传递精确 occurrence 与认证操作者及理由")
  @Test
  void shouldCatchUpDelegatesExactOccurrenceWithAuthenticatedOperatorAndReason() {
    when(operations.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(workflows.getById(23L, "tenant-a")).thenReturn(enabledWorkflow());
    when(workflows.validate(23L, "tenant-a"))
        .thenReturn(new DagValidationResult(true, List.of(), List.of()));
    when(operations.delegateScheduledOccurrenceCatchUp(any()))
        .thenReturn(new ScheduledOccurrenceCatchUpResponse("request-42", "ACCEPTED"));
    Instant fireTime = Instant.parse("2026-10-14T09:30:00Z");
    var request = new ScheduledOccurrenceCatchUpRequest(
        "tenant-a", fireTime, LocalDate.of(2026, 10, 14), "missed scheduled fire");

    var result = service.catchUpRejectedOccurrence(23L, request, "ops-user", "catch-up-key");

    assertThat(result.requestId()).isEqualTo("request-42");
    assertThat(result.status()).isEqualTo("ACCEPTED");
    ArgumentCaptor<ConsoleScheduledOccurrenceCatchUpCommand> command =
        ArgumentCaptor.forClass(ConsoleScheduledOccurrenceCatchUpCommand.class);
    verify(operations).delegateScheduledOccurrenceCatchUp(command.capture());
    assertThat(command.getValue().workflowDefinitionId()).isEqualTo(23L);
    assertThat(command.getValue().scheduledFireTime()).isEqualTo(fireTime);
    assertThat(command.getValue().bizDate()).isEqualTo(LocalDate.of(2026, 10, 14));
    assertThat(command.getValue().requestedBy()).isEqualTo("ops-user");
    assertThat(command.getValue().reason()).isEqualTo("missed scheduled fire");
    assertThat(command.getValue().idempotencyKey()).isEqualTo("catch-up-key");
    verify(operations).publishRefresh("tenant-a");
  }

  @DisplayName("补跑策略要求审批时提交统一审批")
  @Test
  void shouldCatchUpSubmitsCentralApprovalWhenTriggerPolicyRequiresIt() {
    when(operations.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(workflows.getById(23L, "tenant-a")).thenReturn(enabledWorkflow());
    when(workflows.validate(23L, "tenant-a"))
        .thenReturn(new DagValidationResult(true, List.of(), List.of()));
    when(operations.delegateScheduledOccurrenceCatchUp(any()))
        .thenReturn(new ScheduledOccurrenceCatchUpResponse("request-43", "PENDING_APPROVAL"));
    when(operations.submitApproval(any())).thenReturn("approval-43");
    var request = new ScheduledOccurrenceCatchUpRequest(
        "tenant-a",
        Instant.parse("2026-10-14T09:30:00Z"),
        LocalDate.of(2026, 10, 14),
        "manual recovery");

    var result = service.catchUpRejectedOccurrence(23L, request, "ops-user", "catch-up-key");

    assertThat(result.requestId()).isEqualTo("request-43");
    assertThat(result.status()).isEqualTo("PENDING_APPROVAL");
    assertThat(result.approvalId()).isEqualTo("approval-43");
    ArgumentCaptor<ApprovalSubmitContext> approval =
        ArgumentCaptor.forClass(ApprovalSubmitContext.class);
    verify(operations).submitApproval(approval.capture());
    assertThat(approval.getValue().approvalType()).isEqualTo("CATCH_UP");
    assertThat(approval.getValue().targetId()).isEqualTo("request-43");
    assertThat(approval.getValue().idempotencyKey()).startsWith("scheduled-occurrence-approval:");
    assertThat(approval.getValue().idempotencyKey()).hasSizeLessThanOrEqualTo(128);
  }

  @Test
  @DisplayName("按认证租户与原键核对请求，停用定义不阻止只读核对")
  void shouldReadOriginalReceipt_whenDefinitionIsNoLongerEnabled() {
    when(operations.resolveTenant("tenant-a")).thenReturn("tenant-a");
    LocalDate bizDate = LocalDate.of(2026, 10, 24);
    WorkflowTriggerStatusResponse receipt = new WorkflowTriggerStatusResponse(
        "tenant-a", 23L, bizDate, "request-1", "trace-1", "WAITING", Instant.EPOCH);
    when(receipts.selectByIdentity("tenant-a", 23L, "original-key")).thenReturn(receipt);

    WorkflowTriggerStatusResponse actual = service.triggerStatus(23L, "tenant-a", "original-key");

    assertThat(actual).isSameAs(receipt);
    verify(operations).resolveTenant("tenant-a");
    verifyNoInteractions(workflows);
    verify(operations, never()).delegateWorkflowLaunch(any());
  }

  @Test
  @DisplayName("请求不存在时返回未找到，不能伪造空日或允许自动重发")
  void shouldReturnNotFound_whenReceiptHasNotBeenRecorded() {
    when(operations.resolveTenant("tenant-a")).thenReturn("tenant-a");
    assertThatThrownBy(() -> service.triggerStatus(23L, "tenant-a", "original-key"))
        .isInstanceOf(BizException.class);
    verify(operations, never()).delegateWorkflowLaunch(any());
  }

  @Test
  @DisplayName("跨租户核对先由权限守护拒绝，不读取台账")
  void shouldRejectReceiptLookup_whenTenantGuardRejects() {
    when(operations.resolveTenant("other-tenant")).thenThrow(new SecurityException("forbidden"));

    assertThatThrownBy(() -> service.triggerStatus(23L, "other-tenant", "original-key"))
        .isInstanceOf(SecurityException.class);
    verifyNoInteractions(receipts);
  }

  private WorkflowDefinitionDetailResponse enabledWorkflow() {
    return new WorkflowDefinitionDetailResponse(
        23L,
        "tenant-a",
        "DAILY_IMPORT",
        "Daily import",
        "BATCH",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        4,
        true,
        null,
        null,
        null,
        List.of(),
        List.of());
  }
}
