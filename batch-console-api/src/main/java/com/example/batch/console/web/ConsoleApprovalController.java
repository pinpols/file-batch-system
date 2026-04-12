package com.example.batch.console.web;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ConsoleApprovalApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.request.ApprovalActionRequest;
import com.example.batch.console.web.request.BatchApprovalActionRequest;
import com.example.batch.console.web.response.ConsoleBatchApprovalResultResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 控制台审批 REST：单条通过/拒绝与批量审批。 */
@RestController
@Validated
@RequestMapping("/api/console/approvals")
@RequiredArgsConstructor
public class ConsoleApprovalController {

  private final ConsoleApprovalApplicationService approvalApplicationService;
  private final ConsoleResponseFactory responseFactory;

  /** 审批通过。 */
  @PostMapping("/{approvalNo}/approve")
  public CommonResponse<String> approve(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable String approvalNo,
      @Valid @RequestBody ApprovalActionRequest request) {
    return responseFactory.success(
        approvalApplicationService.approve(
            request.getTenantId(), approvalNo, request.getOperatorId(), request.getReason()));
  }

  /** 审批拒绝。 */
  @PostMapping("/{approvalNo}/reject")
  public CommonResponse<String> reject(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable String approvalNo,
      @Valid @RequestBody ApprovalActionRequest request) {
    return responseFactory.success(
        approvalApplicationService.reject(
            request.getTenantId(), approvalNo, request.getOperatorId(), request.getReason()));
  }

  /** 批量审批通过。 */
  @PostMapping("/batch-approve")
  public CommonResponse<List<ConsoleBatchApprovalResultResponse>> batchApprove(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody BatchApprovalActionRequest request) {
    return responseFactory.success(
        approvalApplicationService.batchApprove(
            request.tenantId(), request.approvalNos(), request.operatorId(), request.reason()));
  }

  /** 批量审批拒绝。 */
  @PostMapping("/batch-reject")
  public CommonResponse<List<ConsoleBatchApprovalResultResponse>> batchReject(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody BatchApprovalActionRequest request) {
    return responseFactory.success(
        approvalApplicationService.batchReject(
            request.tenantId(), request.approvalNos(), request.operatorId(), request.reason()));
  }
}
