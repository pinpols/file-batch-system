package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.model.PageResponse;
import com.example.batch.console.application.workflow.ConsolePipelineDefinitionApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.audit.AuditAction;
import com.example.batch.console.support.web.Idempotent;
import com.example.batch.console.web.request.file.PipelineDefinitionSaveRequest;
import com.example.batch.console.web.response.workflow.PipelineDefinitionDetailResponse;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/console/pipeline-definitions")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
@Idempotent
public class ConsolePipelineDefinitionController {

  private final ConsolePipelineDefinitionApplicationService pipelineDefinitionApplicationService;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping
  public CommonResponse<PageResponse<Map<String, Object>>> list(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "jobCode", required = false) String jobCode,
      @RequestParam(value = "pipelineType", required = false) String pipelineType,
      @RequestParam(value = "enabled", required = false) Boolean enabled,
      @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
      @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
    return responseFactory.success(
        pipelineDefinitionApplicationService.list(
            tenantId, jobCode, pipelineType, enabled, pageNo, pageSize));
  }

  @GetMapping("/{id}")
  public CommonResponse<PipelineDefinitionDetailResponse> detail(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(pipelineDefinitionApplicationService.detail(id, tenantId));
  }

  @PostMapping
  @AuditAction(action = "pipelineDefinition.create", aggregateType = "pipeline_definition")
  public CommonResponse<PipelineDefinitionDetailResponse> create(
      @Valid @RequestBody PipelineDefinitionSaveRequest request) {
    return responseFactory.success(pipelineDefinitionApplicationService.create(request));
  }

  @PutMapping("/{id}")
  @AuditAction(
      action = "pipelineDefinition.update",
      aggregateType = "pipeline_definition",
      aggregateId = "#id")
  public CommonResponse<PipelineDefinitionDetailResponse> update(
      @PathVariable Long id, @Valid @RequestBody PipelineDefinitionSaveRequest request) {
    return responseFactory.success(pipelineDefinitionApplicationService.update(id, request));
  }

  @PostMapping("/{id}/toggle")
  @AuditAction(
      action = "pipelineDefinition.toggle",
      aggregateType = "pipeline_definition",
      aggregateId = "#id")
  public CommonResponse<Void> toggle(
      @PathVariable Long id,
      @RequestParam("tenantId") String tenantId,
      @RequestParam("enabled") Boolean enabled) {
    pipelineDefinitionApplicationService.toggle(id, tenantId, enabled);
    return responseFactory.success(null);
  }
}
