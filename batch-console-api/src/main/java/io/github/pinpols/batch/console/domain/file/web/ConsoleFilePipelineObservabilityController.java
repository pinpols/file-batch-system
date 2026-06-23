package io.github.pinpols.batch.console.domain.file.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.domain.file.web.query.FilePipelineQueryRequest;
import io.github.pinpols.batch.console.domain.file.web.response.ConsoleFilePipelineResponse;
import io.github.pinpols.batch.console.domain.observability.application.ConsoleQueryApplicationService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/console")
@PreAuthorize(
    "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_ADMIN', 'ROLE_TENANT_USER')")
@RequiredArgsConstructor
public class ConsoleFilePipelineObservabilityController {

  private final ConsoleQueryApplicationService applicationService;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping("/file-pipeline-observability")
  public CommonResponse<PageResponse<ConsoleFilePipelineResponse>> filePipelineObservability(
      @Valid @ModelAttribute FilePipelineQueryRequest request) {
    return responseFactory.success(applicationService.filePipelines(request));
  }

  @GetMapping("/file-pipeline-observability/{id}")
  public CommonResponse<ConsoleFilePipelineResponse> filePipelineObservabilityDetail(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(applicationService.filePipelineDetail(tenantId, id));
  }
}
