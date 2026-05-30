package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.job.ConsoleJobDefinitionApplicationService;
import com.example.batch.console.domain.audit.support.AuditAction;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.Idempotent;
import com.example.batch.console.web.request.job.BatchEnabledPatchRequest;
import com.example.batch.console.web.request.job.EnabledPatchRequest;
import com.example.batch.console.web.request.job.JobDefinitionCopyRequest;
import com.example.batch.console.web.request.job.JobDefinitionCreateRequest;
import com.example.batch.console.web.request.job.JobDefinitionUpdateRequest;
import com.example.batch.console.web.response.job.ConsoleJobDefinitionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/console/job-definitions")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleJobDefinitionController {

  private final ConsoleJobDefinitionApplicationService jobDefinitionApplicationService;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping("/{id}")
  @PreAuthorize(
      "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_ADMIN'," + " 'ROLE_TENANT_USER')")
  public CommonResponse<ConsoleJobDefinitionResponse> detail(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(jobDefinitionApplicationService.detail(id, tenantId));
  }

  @PostMapping
  @AuditAction(action = "jobDefinition.create", aggregateType = "job_definition")
  public CommonResponse<ConsoleJobDefinitionResponse> create(
      @Valid @RequestBody JobDefinitionCreateRequest request) {
    return responseFactory.success(jobDefinitionApplicationService.create(request));
  }

  @PutMapping("/{id}")
  @AuditAction(
      action = "jobDefinition.update",
      aggregateType = "job_definition",
      aggregateId = "#id")
  public CommonResponse<ConsoleJobDefinitionResponse> update(
      @PathVariable Long id, @Valid @RequestBody JobDefinitionUpdateRequest request) {
    return responseFactory.success(jobDefinitionApplicationService.update(id, request));
  }

  /** 启用/禁用作业定义。 */
  @PatchMapping("/{id}")
  @AuditAction(
      action = "jobDefinition.patch",
      aggregateType = "job_definition",
      aggregateId = "#id")
  public CommonResponse<Void> patch(
      @PathVariable Long id, @Valid @RequestBody EnabledPatchRequest request) {
    jobDefinitionApplicationService.toggle(id, request.getTenantId(), request.getEnabled());
    return responseFactory.success(null);
  }

  /** 批量启用/禁用作业定义。 */
  @PatchMapping("/batch")
  @AuditAction(action = "jobDefinition.batchPatch", aggregateType = "job_definition")
  public CommonResponse<Integer> batchPatch(@Valid @RequestBody BatchEnabledPatchRequest request) {
    return responseFactory.success(
        jobDefinitionApplicationService.batchToggle(
            request.getTenantId(), request.getIds(), request.getEnabled()));
  }

  @PostMapping("/{id}/copy")
  @AuditAction(action = "jobDefinition.copy", aggregateType = "job_definition", aggregateId = "#id")
  public CommonResponse<ConsoleJobDefinitionResponse> copy(
      @PathVariable Long id,
      @RequestParam("tenantId") String tenantId,
      @RequestParam("newJobCode") String newJobCode) {
    return responseFactory.success(jobDefinitionApplicationService.copy(id, tenantId, newJobCode));
  }

  /** 克隆作业定义并可选覆盖字段（脚手架模式）。 */
  @PostMapping("/{id}/clone")
  @AuditAction(
      action = "jobDefinition.clone",
      aggregateType = "job_definition",
      aggregateId = "#id")
  public CommonResponse<ConsoleJobDefinitionResponse> clone(
      @PathVariable Long id, @Valid @RequestBody JobDefinitionCopyRequest request) {
    return responseFactory.success(jobDefinitionApplicationService.copyWithOverrides(id, request));
  }
}
