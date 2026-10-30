package io.github.pinpols.batch.console.web;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.application.config.ConsoleConfigSyncApplicationService;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncExportRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncImportRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncPreviewRequest;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncExportResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncImportResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncLogResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncPreviewResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncRequestStatusResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/console/config/sync")
@PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
@RequiredArgsConstructor
@Idempotent
public class ConsoleConfigSyncController {

  private final ConsoleConfigSyncApplicationService applicationService;
  private final ConsoleResponseFactory responseFactory;

  @PostMapping("/export")
  public CommonResponse<ConfigSyncExportResponse> export(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody ConfigSyncExportRequest request) {
    return responseFactory.success(applicationService.export(request));
  }

  @PostMapping("/preview")
  public CommonResponse<ConfigSyncPreviewResponse> preview(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody ConfigSyncPreviewRequest request) {
    return responseFactory.success(applicationService.preview(request));
  }

  @PostMapping("/import")
  public CommonResponse<ConfigSyncImportResponse> importBundle(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody ConfigSyncImportRequest request) {
    return responseFactory.success(applicationService.importBundle(request));
  }

  /** 按原幂等键读取同步审计，不重新执行导入。 */
  @GetMapping("/requests/status")
  public CommonResponse<ConfigSyncRequestStatusResponse> requestStatus(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) @NotBlank String requestKey,
      @RequestParam("tenantId") @NotBlank String tenantId) {
    ConfigSyncRequestStatusResponse result = applicationService.requestStatus(tenantId, requestKey);
    return responseFactory.success(result);
  }

  @PostMapping("/logs/{logId}/reconcile")
  public CommonResponse<ConfigSyncLogResponse> reconcile(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @RequestParam("tenantId") @NotBlank String tenantId,
      @PathVariable("logId") @Positive Long logId) {
    ConfigSyncLogResponse result = applicationService.reconcile(tenantId, logId);
    return responseFactory.success(result);
  }

  @GetMapping("/logs")
  public CommonResponse<PageResponse<ConfigSyncLogResponse>> logs(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
      @RequestParam(value = "pageSize", defaultValue = "15") int pageSize) {
    return responseFactory.success(applicationService.logs(tenantId, pageNo, pageSize));
  }
}
