package io.github.pinpols.batch.console.web;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.application.config.ConfigBatchOperationRecoveryResponse;
import io.github.pinpols.batch.console.application.config.ConsoleConfigBatchOperationService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.support.web.ConsoleIdempotencyRequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 按原请求身份核对配置批操作结果。 */
@RestController
@RequestMapping("/api/console/config/batch-operation")
@PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
@RequiredArgsConstructor
public class ConsoleConfigBatchRecoveryController {

  private final ConsoleConfigBatchOperationService recoveryService;
  private final TenantIdResolver tenantGuard;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping
  public CommonResponse<ConfigBatchOperationRecoveryResponse> find(
      @RequestParam String auditTenantId,
      @RequestParam String operationKind,
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      HttpServletRequest request) {
    tenantGuard.assertTenantAllowed(auditTenantId);
    if (!Texts.hasText(idempotencyKey)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument");
    }
    String originalPath =
        switch (operationKind) {
          case "TENANT_COPY" -> "/api/console/config/tenant-copy";
          case "JOB_BUNDLE_CREATE" -> "/api/console/jobs/bundle/create";
          case "JOB_BUNDLE_IMPORT" -> "/api/console/jobs/bundle/import";
          default ->
            throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument");
        };
    String tenantHeader = request.getHeader(CommonConstants.DEFAULT_TENANT_ID_HEADER);
    String originalUri = request.getContextPath() + originalPath;
    String hash = ConsoleIdempotencyRequestIdentity.requestHash(
        tenantHeader, "POST", originalUri, idempotencyKey);
    ConfigBatchOperationRecoveryResponse result =
        recoveryService.find(auditTenantId, hash, operationKind);
    return responseFactory.success(result);
  }
}
