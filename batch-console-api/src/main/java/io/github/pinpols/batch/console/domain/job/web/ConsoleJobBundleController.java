package io.github.pinpols.batch.console.domain.job.web;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobBundleApplicationService;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobBundleCreateRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobBundleImportRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobBundleExportResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobBundleResultResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleIdempotencyRequestIdentity;
import io.github.pinpols.batch.console.support.web.Idempotent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/console/jobs/bundle")
@PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
@RequiredArgsConstructor
@Idempotent
public class ConsoleJobBundleController {

  private final ConsoleJobBundleApplicationService applicationService;
  private final ConsoleResponseFactory responseFactory;

  @PostMapping("/create")
  public CommonResponse<ConsoleJobBundleResultResponse> create(
      @Valid @RequestBody JobBundleCreateRequest request, HttpServletRequest httpRequest) {
    String requestHash = requestHash(httpRequest);
    ConsoleJobBundleResultResponse result = requestHash == null
        ? applicationService.create(request)
        : applicationService.create(request, requestHash);
    return responseFactory.success(result);
  }

  @GetMapping("/export")
  public CommonResponse<ConsoleJobBundleExportResponse> export(
      @RequestParam("tenantId") String tenantId, @RequestParam("jobCode") String jobCode) {
    return responseFactory.success(applicationService.exportBundle(tenantId, jobCode));
  }

  @PostMapping("/import")
  public CommonResponse<ConsoleJobBundleResultResponse> importBundle(
      @Valid @RequestBody JobBundleImportRequest request, HttpServletRequest httpRequest) {
    String requestHash = requestHash(httpRequest);
    ConsoleJobBundleResultResponse result = requestHash == null
        ? applicationService.importBundle(request)
        : applicationService.importBundle(request, requestHash);
    return responseFactory.success(result);
  }

  private String requestHash(HttpServletRequest request) {
    String key = request.getHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER);
    if (!Texts.hasText(key)) {
      return null;
    }
    String tenantHeader = request.getHeader(CommonConstants.DEFAULT_TENANT_ID_HEADER);
    return ConsoleIdempotencyRequestIdentity.requestHash(
        tenantHeader, request.getMethod(), request.getRequestURI(), key);
  }
}
