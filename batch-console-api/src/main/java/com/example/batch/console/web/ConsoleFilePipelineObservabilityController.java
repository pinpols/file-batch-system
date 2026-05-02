package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.model.PageResponse;
import com.example.batch.console.application.ConsoleQueryApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.query.FilePipelineQueryRequest;
import com.example.batch.console.web.response.file.ConsoleFilePipelineResponse;
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
    "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN', 'ROLE_TENANT_USER')")
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
