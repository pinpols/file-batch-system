package com.example.batch.console.domain.workflow.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.domain.workflow.application.ConsoleWorkflowDefinitionApplicationService;
import com.example.batch.console.domain.workflow.application.ConsoleWorkflowDefinitionApplicationService.DagValidationResult;
import com.example.batch.console.domain.workflow.infrastructure.WorkflowMermaidRenderer;
import com.example.batch.console.domain.workflow.web.request.WorkflowDefinitionSaveRequest;
import com.example.batch.console.domain.workflow.web.response.WorkflowDefinitionDetailResponse;
import com.example.batch.console.domain.workflow.web.response.WorkflowMermaidResponse;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.Idempotent;
import com.example.batch.console.web.request.job.EnabledPatchRequest;
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
      "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_ADMIN'," + " 'ROLE_TENANT_USER')")
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
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_ADMIN')")
  public CommonResponse<DagValidationResult> validate(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(workflowDefinitionApplicationService.validate(id, tenantId));
  }

  /**
   * 把 workflow 渲染为 mermaid flowchart 文本,可贴入 GitHub README / PR / 文档站。运行时 viewer 与 docs/PR review
   * 两种场景共享同一图形语言。
   */
  @GetMapping("/{id}/mermaid")
  @PreAuthorize(
      "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_ADMIN'," + " 'ROLE_TENANT_USER')")
  public CommonResponse<WorkflowMermaidResponse> mermaid(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    WorkflowDefinitionDetailResponse detail =
        workflowDefinitionApplicationService.getById(id, tenantId);
    return responseFactory.success(
        new WorkflowMermaidResponse(WorkflowMermaidRenderer.render(detail)));
  }
}
