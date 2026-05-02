package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ConsoleWorkflowDefinitionApplicationService;
import com.example.batch.console.application.ConsoleWorkflowDefinitionApplicationService.DagValidationResult;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.Idempotent;
import com.example.batch.console.web.request.job.EnabledPatchRequest;
import com.example.batch.console.web.request.workflow.WorkflowDefinitionSaveRequest;
import com.example.batch.console.web.response.workflow.WorkflowDefinitionDetailResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/console/workflow-definitions")
@RequiredArgsConstructor
@Idempotent
public class ConsoleWorkflowDefinitionController {

  private final ConsoleWorkflowDefinitionApplicationService workflowDefinitionApplicationService;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping("/{id}")
  @PreAuthorize(
      "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN'," + " 'ROLE_TENANT_USER')")
  public CommonResponse<WorkflowDefinitionDetailResponse> getById(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(workflowDefinitionApplicationService.getById(id, tenantId));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<WorkflowDefinitionDetailResponse> create(
      @Valid @RequestBody WorkflowDefinitionSaveRequest request) {
    return responseFactory.success(workflowDefinitionApplicationService.create(request));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<WorkflowDefinitionDetailResponse> update(
      @PathVariable Long id, @Valid @RequestBody WorkflowDefinitionSaveRequest request) {
    return responseFactory.success(workflowDefinitionApplicationService.update(id, request));
  }

  /** 启用/禁用工作流定义。 */
  @PatchMapping("/{id}")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<Void> patch(
      @PathVariable Long id, @Valid @RequestBody EnabledPatchRequest request) {
    workflowDefinitionApplicationService.toggleEnabled(
        id, request.getTenantId(), request.getEnabled());
    return responseFactory.success(null);
  }

  @PostMapping("/{id}/validate")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
  public CommonResponse<DagValidationResult> validate(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(workflowDefinitionApplicationService.validate(id, tenantId));
  }
}
