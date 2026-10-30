package io.github.pinpols.batch.console.domain.workflow.infrastructure;

import io.github.pinpols.batch.common.dto.ScheduledOccurrenceCatchUpResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.console.application.ops.ConsoleJobOperationsPort;
import io.github.pinpols.batch.console.domain.workflow.application.ConsoleWorkflowTriggerService;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDefinitionService;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDefinitionService.DagValidationResult;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.ScheduledOccurrenceCatchUpRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowTriggerRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.WorkflowDefinitionDetailResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.WorkflowTriggerStatusResponse;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowTriggerReceiptMapper;
import io.github.pinpols.batch.console.shared.command.ApprovalSubmitContext;
import io.github.pinpols.batch.console.shared.command.ConsoleCatchUpApprovalRequest;
import io.github.pinpols.batch.console.shared.command.ConsoleScheduledOccurrenceCatchUpCommand;
import io.github.pinpols.batch.console.shared.command.ConsoleWorkflowLaunchCommand;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultConsoleWorkflowTriggerService implements ConsoleWorkflowTriggerService {

  private final ConsoleJobOperationsPort operations;
  private final WorkflowDefinitionService workflowDefinitions;
  private final WorkflowTriggerReceiptMapper triggerReceipts;

  @Override
  public WorkflowTriggerStatusResponse triggerStatus(
      Long workflowDefinitionId, String requestedTenantId, String idempotencyKey) {
    if (EmptyChecks.isNull(workflowDefinitionId) || workflowDefinitionId <= 0) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.request_body_required");
    }
    if (EmptyChecks.isBlank(idempotencyKey)) {
      throw BizException.of(
          ResultCode.MISSING_IDEMPOTENCY_KEY,
          ResultCode.MISSING_IDEMPOTENCY_KEY.detailKey(),
          ResultCode.MISSING_IDEMPOTENCY_KEY.defaultMessage());
    }
    String tenantId = operations.resolveTenant(requestedTenantId);
    // 定义停用后仍能核对先前请求；查不到也不能证明在途请求从未执行。
    WorkflowTriggerStatusResponse receipt =
        triggerReceipts.selectByIdentity(tenantId, workflowDefinitionId, idempotencyKey);
    return Guard.requireFound(receipt, "workflow trigger request");
  }

  @Override
  public String trigger(
      Long workflowDefinitionId, WorkflowTriggerRequest request, String idempotencyKey) {
    if (EmptyChecks.isNull(workflowDefinitionId)
        || workflowDefinitionId <= 0
        || EmptyChecks.isNull(request)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.request_body_required");
    }
    String tenantId = operations.resolveTenant(request.tenantId());
    WorkflowDefinitionDetailResponse workflow =
        workflowDefinitions.getById(workflowDefinitionId, tenantId);
    if (!Boolean.TRUE.equals(workflow.enabled())) {
      throw BizException.of(ResultCode.VALIDATION_ERROR, "error.workflow.definition_disabled");
    }
    DagValidationResult validation = workflowDefinitions.validate(workflowDefinitionId, tenantId);
    if (!validation.valid()) {
      throw BizException.of(ResultCode.VALIDATION_ERROR, "error.workflow.definition_invalid");
    }
    TriggerType triggerType =
        operations.resolveTriggerType(request.triggerType(), TriggerType.MANUAL);
    Map<String, Object> params = operations.parsePayload(request.payload());
    LocalDate bizDate = operations.parseBizDate(request.bizDate());
    ConsoleWorkflowLaunchCommand command = new ConsoleWorkflowLaunchCommand(
        tenantId, workflowDefinitionId, bizDate, triggerType, params, idempotencyKey);
    String instanceNo = operations.delegateWorkflowLaunch(command);
    operations.publishRefresh(tenantId);
    return instanceNo;
  }

  @Override
  public io.github.pinpols.batch.console.domain.workflow.application.contract.response
          .ScheduledOccurrenceCatchUpResponse
      catchUpRejectedOccurrence(
          Long workflowDefinitionId,
          ScheduledOccurrenceCatchUpRequest request,
          String requestedBy,
          String idempotencyKey) {
    if (EmptyChecks.isNull(workflowDefinitionId)
        || workflowDefinitionId <= 0
        || EmptyChecks.isNull(request)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.request_body_required");
    }
    String tenantId = operations.resolveTenant(request.tenantId());
    WorkflowDefinitionDetailResponse workflow =
        workflowDefinitions.getById(workflowDefinitionId, tenantId);
    if (!Boolean.TRUE.equals(workflow.enabled())) {
      throw BizException.of(ResultCode.VALIDATION_ERROR, "error.workflow.definition_disabled");
    }
    DagValidationResult validation = workflowDefinitions.validate(workflowDefinitionId, tenantId);
    if (!validation.valid()) {
      throw BizException.of(ResultCode.VALIDATION_ERROR, "error.workflow.definition_invalid");
    }
    ConsoleScheduledOccurrenceCatchUpCommand command = new ConsoleScheduledOccurrenceCatchUpCommand(
        tenantId,
        workflowDefinitionId,
        request.scheduledFireTime(),
        request.bizDate(),
        requestedBy,
        request.reason().trim(),
        idempotencyKey);
    ScheduledOccurrenceCatchUpResponse result =
        operations.delegateScheduledOccurrenceCatchUp(command);
    String approvalId = null;
    if ("PENDING_APPROVAL".equals(result.status())) {
      ConsoleCatchUpApprovalRequest approvalRequest = new ConsoleCatchUpApprovalRequest();
      approvalRequest.setTenantId(tenantId);
      approvalRequest.setWorkflowDefinitionId(workflowDefinitionId);
      approvalRequest.setRequestId(result.requestId());
      approvalRequest.setBizDate(request.bizDate().toString());
      approvalRequest.setReason(request.reason().trim());
      ApprovalSubmitContext.ApprovalSubmitContextBuilder approvalBuilder =
          ApprovalSubmitContext.builder()
              .approvalType("CATCH_UP")
              .actionType("CATCH_UP")
              .targetType("CATCH_UP")
              .targetId(result.requestId())
              .payload(approvalRequest)
              .approvalReason(request.reason().trim());
      String approvalKey = approvalIdempotencyKey(result.requestId());
      ApprovalSubmitContext approval =
          approvalBuilder.idempotencyKey(approvalKey).build();
      approvalId = operations.submitApproval(approval);
      if (EmptyChecks.isBlank(approvalId)) {
        throw BizException.of(ResultCode.SYSTEM_ERROR, "error.approval.submit_failed");
      }
    }
    operations.publishRefresh(tenantId);
    return new io.github.pinpols.batch.console.domain.workflow.application.contract.response
        .ScheduledOccurrenceCatchUpResponse(result.requestId(), result.status(), approvalId);
  }

  private String approvalIdempotencyKey(String requestId) {
    String keySeed = "scheduled-occurrence-approval:" + requestId;
    byte[] keySeedBytes = keySeed.getBytes(StandardCharsets.UTF_8);
    UUID deterministicId = UUID.nameUUIDFromBytes(keySeedBytes);
    String deterministicIdText = deterministicId.toString();
    return "scheduled-occurrence-approval:" + deterministicIdText;
  }
}
