package com.example.batch.orchestrator.controller;

import com.example.batch.orchestrator.application.service.ApprovalWorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
