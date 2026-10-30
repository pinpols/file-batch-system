package io.github.pinpols.batch.console.domain.job.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.job.application.BatchDayCatchUpRecoveryService;
import io.github.pinpols.batch.console.domain.job.application.contract.request.BatchDayCatchUpAbandonRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.BatchDayCatchUpContinueRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.BatchDayCatchUpReconcileRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.BatchDayCatchUpRequestStatusResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.shared.audit.AuditAction;
import io.github.pinpols.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 原批次审批回查和待处理项目接续入口。 */
@RestController
@Validated
@RequestMapping("/api/console/jobs/batch-days/catchup/requests")
@RequiredArgsConstructor
public class ConsoleBatchDayCatchUpRecoveryController {
  private final BatchDayCatchUpRecoveryService recoveryService;
  private final ConsoleResponseFactory responseFactory;

  @PostMapping("/abandon")
  @AuditAction(
      action = "batch_day.catch_up_abandon_unprepared",
      aggregateType = "batch_day_catch_up",
      targetTenantParam = "#request.tenantId",
      recordParams = false)
  @Idempotent
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
  public CommonResponse<BatchDayCatchUpRequestStatusResponse> abandonUnprepared(
      @RequestHeader("X-Original-Idempotency-Key") @NotBlank String originalKey,
      @Valid @RequestBody BatchDayCatchUpAbandonRequest request) {
    BatchDayCatchUpRequestStatusResponse result = recoveryService.abandonUnprepared(
        request.tenantId(), originalKey, request.bizDate(), request.calendarCode());
    return responseFactory.success(result);
  }

  @PostMapping("/continue")
  @AuditAction(
      action = "batch_day.catch_up_continue",
      aggregateType = "batch_day_catch_up",
      targetTenantParam = "#request.tenantId",
      recordParams = false)
  @Idempotent
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
  public CommonResponse<BatchDayCatchUpRequestStatusResponse> continueRequest(
      @RequestHeader("X-Original-Idempotency-Key") @NotBlank String originalKey,
      @Valid @RequestBody BatchDayCatchUpContinueRequest request) {
    BatchDayCatchUpRequestStatusResponse result =
        recoveryService.continueRequest(request.tenantId(), originalKey);
    return responseFactory.success(result);
  }

  @PostMapping("/reconcile")
  @AuditAction(
      action = "batch_day.catch_up_reconcile",
      aggregateType = "batch_day_catch_up",
      aggregateId = "#request.jobCode",
      targetTenantParam = "#request.tenantId",
      recordParams = false)
  @Idempotent
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
  public CommonResponse<BatchDayCatchUpRequestStatusResponse> reconcile(
      @RequestHeader("X-Original-Idempotency-Key") @NotBlank String originalKey,
      @Valid @RequestBody BatchDayCatchUpReconcileRequest request) {
    BatchDayCatchUpRequestStatusResponse result =
        recoveryService.reconcile(request.tenantId(), originalKey, request.jobCode());
    return responseFactory.success(result);
  }
}
