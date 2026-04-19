package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ConsoleJobDefinitionApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.request.BatchEnabledPatchRequest;
import com.example.batch.console.web.request.EnabledPatchRequest;
import com.example.batch.console.web.request.JobDefinitionCopyRequest;
import com.example.batch.console.web.request.JobDefinitionCreateRequest;
import com.example.batch.console.web.request.JobDefinitionUpdateRequest;
import com.example.batch.console.web.response.ConsoleJobDefinitionResponse;
import com.example.batch.console.support.Idempotent;
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
      "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN'," + " 'ROLE_TENANT_USER')")
  public CommonResponse<ConsoleJobDefinitionResponse> detail(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(jobDefinitionApplicationService.detail(id, tenantId));
  }

  @PostMapping
  public CommonResponse<ConsoleJobDefinitionResponse> create(
      @Valid @RequestBody JobDefinitionCreateRequest request) {
    return responseFactory.success(jobDefinitionApplicationService.create(request));
  }

  @PutMapping("/{id}")
  public CommonResponse<ConsoleJobDefinitionResponse> update(
      @PathVariable Long id, @Valid @RequestBody JobDefinitionUpdateRequest request) {
    return responseFactory.success(jobDefinitionApplicationService.update(id, request));
  }

  /** 启用/禁用作业定义。 */
  @PatchMapping("/{id}")
  public CommonResponse<Void> patch(
      @PathVariable Long id, @Valid @RequestBody EnabledPatchRequest request) {
    jobDefinitionApplicationService.toggle(id, request.getTenantId(), request.getEnabled());
    return responseFactory.success(null);
  }

  /** 批量启用/禁用作业定义。 */
  @PatchMapping("/batch")
  public CommonResponse<Integer> batchPatch(@Valid @RequestBody BatchEnabledPatchRequest request) {
    return responseFactory.success(
        jobDefinitionApplicationService.batchToggle(
            request.getTenantId(), request.getIds(), request.getEnabled()));
  }

  @PostMapping("/{id}/copy")
  public CommonResponse<ConsoleJobDefinitionResponse> copy(
      @PathVariable Long id,
      @RequestParam("tenantId") String tenantId,
      @RequestParam("newJobCode") String newJobCode) {
    return responseFactory.success(jobDefinitionApplicationService.copy(id, tenantId, newJobCode));
  }

  /** 克隆作业定义并可选覆盖字段（脚手架模式）。 */
  @PostMapping("/{id}/clone")
  public CommonResponse<ConsoleJobDefinitionResponse> clone(
      @PathVariable Long id, @Valid @RequestBody JobDefinitionCopyRequest request) {
    return responseFactory.success(jobDefinitionApplicationService.copyWithOverrides(id, request));
  }
}
