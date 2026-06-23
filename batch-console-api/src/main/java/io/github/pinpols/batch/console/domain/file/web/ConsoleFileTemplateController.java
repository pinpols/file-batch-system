package io.github.pinpols.batch.console.domain.file.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.domain.file.application.ConsoleFileTemplateApplicationService;
import io.github.pinpols.batch.console.domain.file.web.query.FileTemplateQueryRequest;
import io.github.pinpols.batch.console.domain.file.web.request.FileTemplateCreateRequest;
import io.github.pinpols.batch.console.domain.file.web.request.FileTemplateUpdateRequest;
import io.github.pinpols.batch.console.domain.job.web.request.EnabledPatchRequest;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 文件模板（file_template_config）CRUD REST 接口。 */
@RestController
@Validated
@RequestMapping("/api/console/file-templates")
@RequiredArgsConstructor
@Idempotent
public class ConsoleFileTemplateController {

  private final ConsoleFileTemplateApplicationService fileTemplateApplicationService;
  private final ConsoleResponseFactory responseFactory;

  /** 分页查询文件模板列表。 */
  @GetMapping
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN', 'ROLE_AUDITOR')")
  public CommonResponse<PageResponse<Map<String, Object>>> list(
      @Valid @ModelAttribute FileTemplateQueryRequest request) {
    return responseFactory.success(fileTemplateApplicationService.list(request));
  }

  /** 获取文件模板详情。 */
  @GetMapping("/{id}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN', 'ROLE_AUDITOR')")
  public CommonResponse<Map<String, Object>> get(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(fileTemplateApplicationService.get(id, tenantId));
  }

  /** 新建文件模板。 */
  @PostMapping
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
  public CommonResponse<Map<String, Object>> create(
      @Valid @RequestBody FileTemplateCreateRequest request) {
    return responseFactory.success(fileTemplateApplicationService.create(request));
  }

  /** 更新文件模板。 */
  @PutMapping("/{id}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
  public CommonResponse<Map<String, Object>> update(
      @PathVariable Long id, @Valid @RequestBody FileTemplateUpdateRequest request) {
    return responseFactory.success(fileTemplateApplicationService.update(id, request));
  }

  /** 启用/禁用文件模板。 */
  @PatchMapping("/{id}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
  public CommonResponse<Void> patch(
      @PathVariable Long id, @Valid @RequestBody EnabledPatchRequest request) {
    fileTemplateApplicationService.toggle(id, request.getTenantId(), request.getEnabled());
    return responseFactory.success(null);
  }
}
