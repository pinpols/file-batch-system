package io.github.pinpols.batch.console.domain.job.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobBundleApplicationService;
import io.github.pinpols.batch.console.domain.job.web.request.JobBundleCreateRequest;
import io.github.pinpols.batch.console.domain.job.web.request.JobBundleImportRequest;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import java.util.Map;
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
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleJobBundleController {

  private final ConsoleJobBundleApplicationService applicationService;
  private final ConsoleResponseFactory responseFactory;

  @PostMapping("/create")
  public CommonResponse<Map<String, Object>> create(
      @Valid @RequestBody JobBundleCreateRequest request) {
    return responseFactory.success(applicationService.create(request));
  }

  @GetMapping("/export")
  public CommonResponse<Map<String, Object>> export(
      @RequestParam("tenantId") String tenantId, @RequestParam("jobCode") String jobCode) {
    return responseFactory.success(applicationService.exportBundle(tenantId, jobCode));
  }

  @PostMapping("/import")
  public CommonResponse<Map<String, Object>> importBundle(
      @Valid @RequestBody JobBundleImportRequest request) {
    return responseFactory.success(applicationService.importBundle(request));
  }
}
