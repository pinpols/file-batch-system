package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.governance.ApprovalWorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审批工作流内部控制器，基础路径 {@code /internal/approvals}。 支持提交审批单（{@code POST /}）、查询审批记录（{@code GET
 * /{approvalNo}}）、按来源请求键回查（{@code GET /by-source-key}）、审批通过（{@code POST /{approvalNo}/approve}）、驳回（{@code POST /{approvalNo}/reject}）
 * 以及标记执行完成（{@code POST /{approvalNo}/executed}）等全流程操作。 仅限内部服务调用，不对外暴露。
 */
@RestController
@RequestMapping("/internal/approvals")
@RequiredArgsConstructor
public class ApprovalController {

  private final ApprovalWorkflowService approvalWorkflowService;

  @PostMapping
  public ApprovalResponse submit(@RequestBody ApprovalRequest request) {
    String tenantId = request.tenantId();
    ApprovalWorkflowService.ApprovalTarget target = new ApprovalWorkflowService.ApprovalTarget(
        request.approvalType(),
        request.actionType(),
        request.targetType(),
        request.targetId(),
        request.payloadJson());
    ApprovalWorkflowService.ApprovalSource source = new ApprovalWorkflowService.ApprovalSource(
        request.requesterId(),
        request.sourceTraceId(),
        request.sourceIdempotencyKey(),
        request.approvalReason());
    ApprovalWorkflowService.ApprovalSubmitCommand command =
        ApprovalWorkflowService.ApprovalSubmitCommand.of(tenantId, target, source);
    String approvalNo = approvalWorkflowService.submit(command);
    return new ApprovalResponse(approvalNo);
  }

  @GetMapping("/{approvalNo}")
  public ApprovalRecordResponse get(
      @PathVariable String approvalNo, @RequestParam String tenantId) {
    ApprovalWorkflowService.ApprovalRecord record =
        approvalWorkflowService.get(tenantId, approvalNo);
    return new ApprovalRecordResponse(record);
  }

  /** 按原来源请求键回查已持久化审批；未找到不能推断远端请求必然未提交。 */
  @GetMapping("/by-source-key")
  public ApprovalRecordResponse getBySourceKey(
      @RequestParam(required = false) String tenantId,
      @RequestHeader(value = CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, required = false)
          String sourceIdempotencyKey) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(sourceIdempotencyKey)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument");
    }
    return new ApprovalRecordResponse(
        approvalWorkflowService.getBySourceKey(tenantId, sourceIdempotencyKey));
  }

  @PostMapping("/{approvalNo}/approve")
  public ApprovalRecordResponse approve(
      @PathVariable String approvalNo, @RequestBody ApprovalActionRequest request) {
    ApprovalWorkflowService.ApprovalRecord record = approvalWorkflowService.approve(
        request.tenantId(), approvalNo, request.operatorId(), request.reason());
    return new ApprovalRecordResponse(record);
  }

  @PostMapping("/{approvalNo}/reject")
  public ApprovalRecordResponse reject(
      @PathVariable String approvalNo, @RequestBody ApprovalActionRequest request) {
    ApprovalWorkflowService.ApprovalRecord record = approvalWorkflowService.reject(
        request.tenantId(), approvalNo, request.operatorId(), request.reason());
    return new ApprovalRecordResponse(record);
  }

  @PostMapping("/{approvalNo}/executed")
  public ApprovalRecordResponse executed(
      @PathVariable String approvalNo, @RequestBody ApprovalTenantRequest request) {
    ApprovalWorkflowService.ApprovalRecord record =
        approvalWorkflowService.markExecuted(request.tenantId(), approvalNo);
    return new ApprovalRecordResponse(record);
  }

  public record ApprovalRequest(
      String tenantId,
      String approvalType,
      String actionType,
      String targetType,
      String targetId,
      String payloadJson,
      String requesterId,
      String sourceTraceId,
      String sourceIdempotencyKey,
      String approvalReason) {}

  public record ApprovalActionRequest(String tenantId, String operatorId, String reason) {}

  public record ApprovalTenantRequest(String tenantId) {}

  public record ApprovalResponse(String approvalNo) {}

  public record ApprovalRecordResponse(ApprovalWorkflowService.ApprovalRecord record) {}
}
