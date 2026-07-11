package io.github.pinpols.batch.console.web;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.application.config.ConsoleConfigSyncApplicationService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.Idempotent;
import io.github.pinpols.batch.console.web.request.config.ConfigSyncExportRequest;
import io.github.pinpols.batch.console.web.request.config.ConfigSyncImportRequest;
import io.github.pinpols.batch.console.web.request.config.ConfigSyncPreviewRequest;
import io.github.pinpols.batch.console.web.response.config.ConfigSyncExportResponse;
import io.github.pinpols.batch.console.web.response.config.ConfigSyncImportResponse;
import io.github.pinpols.batch.console.web.response.config.ConfigSyncLogResponse;
import io.github.pinpols.batch.console.web.response.config.ConfigSyncPreviewResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/console/config/sync")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
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

  @GetMapping("/logs")
  public CommonResponse<List<ConfigSyncLogResponse>> logs(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "limit", defaultValue = "50") int limit) {
    return responseFactory.success(applicationService.logs(tenantId, limit));
  }
}
