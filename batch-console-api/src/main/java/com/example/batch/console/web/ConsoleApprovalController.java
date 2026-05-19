package com.example.batch.console.web;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ops.ConsoleApprovalApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.audit.AuditAction;
import com.example.batch.console.support.web.Idempotent;
import com.example.batch.console.web.request.ops.ApprovalActionRequest;
import com.example.batch.console.web.request.ops.BatchApprovalActionRequest;
import com.example.batch.console.web.response.ops.ConsoleBatchApprovalResultResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台审批 REST：单条通过/拒绝与批量审批。
 *
 * <p>P0-1 角色授权（ADR audit 2026-05-14）：审批是高危业务操作，全部要求 {@code ROLE_ADMIN}/{@code
 * ROLE_CONFIG_ADMIN}/{@code ROLE_AUDITOR} 之一。普通租户用户不能审批。
 */
@RestController
@Validated
@RequestMapping("/api/console/approvals")
@RequiredArgsConstructor
@Idempotent
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_CONFIG_ADMIN','ROLE_AUDITOR')")
public class ConsoleApprovalController {

  // R3-P2-6：审计独立 logger。logback.xml 可单独路由 audit 到独立 appender（独立日志文件/SIEM/Kafka）
  // 实时告警；DB 表审计（AuditLogMapper）继续作为查询源，双轨互不依赖。
  private static final Logger AUDIT = LoggerFactory.getLogger("audit.console.approval");

  private final ConsoleApprovalApplicationService approvalApplicationService;
  private final ConsoleResponseFactory responseFactory;
  // R4-P0-2：所有 approve/reject 入口必须用 tenantGuard 校验请求体 tenantId 是否与 JWT 持有的 tenantId 一致，
  // 防止租户角色用户改 body tenantId 批准其他租户的 approvalNo。
  private final com.example.batch.console.support.auth.ConsoleTenantGuard tenantGuard;

  /** 审批通过。 */
  @PostMapping("/{approvalNo}/approve")
  @AuditAction(action = "approval.approve", aggregateType = "approval", aggregateId = "#approvalNo")
  public CommonResponse<String> approve(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable String approvalNo,
      @Valid @RequestBody ApprovalActionRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    String result =
        approvalApplicationService.approve(
            tenantId, approvalNo, request.getOperatorId(), request.getReason());
    auditApprovalAction(
        "approve", tenantId, approvalNo, request.getOperatorId(), request.getReason());
    return responseFactory.success(result);
  }

  /** 审批拒绝。 */
  @PostMapping("/{approvalNo}/reject")
  @AuditAction(action = "approval.reject", aggregateType = "approval", aggregateId = "#approvalNo")
  public CommonResponse<String> reject(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable String approvalNo,
      @Valid @RequestBody ApprovalActionRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    String result =
        approvalApplicationService.reject(
            tenantId, approvalNo, request.getOperatorId(), request.getReason());
    auditApprovalAction(
        "reject", tenantId, approvalNo, request.getOperatorId(), request.getReason());
    return responseFactory.success(result);
  }

  /** 批量审批通过。 */
  @PostMapping("/batch-approve")
  @AuditAction(action = "approval.batchApprove", aggregateType = "approval")
  public CommonResponse<List<ConsoleBatchApprovalResultResponse>> batchApprove(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody BatchApprovalActionRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.tenantId());
    List<ConsoleBatchApprovalResultResponse> result =
        approvalApplicationService.batchApprove(
            tenantId, request.approvalNos(), request.operatorId(), request.reason());
    auditApprovalAction(
        "batch-approve",
        tenantId,
        String.valueOf(request.approvalNos()),
        request.operatorId(),
        request.reason());
    return responseFactory.success(result);
  }

  /** 批量审批拒绝。 */
  @PostMapping("/batch-reject")
  @AuditAction(action = "approval.batchReject", aggregateType = "approval")
  public CommonResponse<List<ConsoleBatchApprovalResultResponse>> batchReject(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody BatchApprovalActionRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.tenantId());
    List<ConsoleBatchApprovalResultResponse> result =
        approvalApplicationService.batchReject(
            tenantId, request.approvalNos(), request.operatorId(), request.reason());
    auditApprovalAction(
        "batch-reject",
        tenantId,
        String.valueOf(request.approvalNos()),
        request.operatorId(),
        request.reason());
    return responseFactory.success(result);
  }

  /**
   * R3-P2-6：审批 audit logger 双轨——DB 审计（AuditLogMapper）继续作为查询源， 此处通过独立 logger 输出结构化 INFO 行，可被
   * logback.xml 路由到独立 appender（独立日志文件 / SIEM / Kafka）。 字段固定顺序便于结构化采集；不输出 reason 全文以防 PII 泄漏。
   */
  private void auditApprovalAction(
      String action, String tenantId, String target, String operatorId, String reason) {
    org.springframework.security.core.Authentication auth =
        SecurityContextHolder.getContext().getAuthentication();
    String actor = auth == null ? "anonymous" : String.valueOf(auth.getName());
    int reasonLen = reason == null ? 0 : reason.length();
    AUDIT.info(
        "action={} actor={} tenant={} target={} operatorId={} reasonLength={}",
        action,
        actor,
        tenantId,
        target,
        operatorId,
        reasonLen);
  }
}
