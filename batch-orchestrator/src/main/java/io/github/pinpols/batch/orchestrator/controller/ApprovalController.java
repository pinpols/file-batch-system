package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.orchestrator.application.service.governance.ApprovalWorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审批工作流内部控制器，基础路径 {@code /internal/approvals}。 支持提交审批单（{@code POST /}）、查询审批记录（{@code GET
 * /{approvalNo}}）、 审批通过（{@code POST /{approvalNo}/approve}）、驳回（{@code POST /{approvalNo}/reject}）
 * 以及标记执行完成（{@code POST /{approvalNo}/executed}）等全流程操作。 仅限内部服务调用，不对外暴露。
 */
@RestController
@RequestMapping("/internal/approvals")
@RequiredArgsConstructor
public class ApprovalController {

  private final ApprovalWorkflowService approvalWorkflowService;

  @PostMapping
  public ApprovalResponse submit(@RequestBody ApprovalRequest request) {
    return new ApprovalResponse(
        approvalWorkflowService.submit(
            ApprovalWorkflowService.ApprovalSubmitCommand.of(
                request.tenantId(),
                new ApprovalWorkflowService.ApprovalTarget(
                    request.approvalType(),
                    request.actionType(),
                    request.targetType(),
                    request.targetId(),
                    request.payloadJson()),
                new ApprovalWorkflowService.ApprovalSource(
                    request.requesterId(),
                    request.sourceTraceId(),
                    request.sourceIdempotencyKey(),
                    request.approvalReason()))));
  }

  @GetMapping("/{approvalNo}")
  public ApprovalRecordResponse get(
      @PathVariable String approvalNo, @RequestParam String tenantId) {
    return new ApprovalRecordResponse(approvalWorkflowService.get(tenantId, approvalNo));
  }

  @PostMapping("/{approvalNo}/approve")
  public ApprovalRecordResponse approve(
      @PathVariable String approvalNo, @RequestBody ApprovalActionRequest request) {
    return new ApprovalRecordResponse(
        approvalWorkflowService.approve(
            request.tenantId(), approvalNo, request.operatorId(), request.reason()));
  }

  @PostMapping("/{approvalNo}/reject")
  public ApprovalRecordResponse reject(
      @PathVariable String approvalNo, @RequestBody ApprovalActionRequest request) {
    return new ApprovalRecordResponse(
        approvalWorkflowService.reject(
            request.tenantId(), approvalNo, request.operatorId(), request.reason()));
  }

  @PostMapping("/{approvalNo}/executed")
  public ApprovalRecordResponse executed(
      @PathVariable String approvalNo, @RequestBody ApprovalTenantRequest request) {
    return new ApprovalRecordResponse(
        approvalWorkflowService.markExecuted(request.tenantId(), approvalNo));
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
