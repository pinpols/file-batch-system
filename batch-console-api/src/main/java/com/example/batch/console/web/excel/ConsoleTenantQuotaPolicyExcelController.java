package com.example.batch.console.web.excel;

import com.example.batch.console.application.ConsoleTenantQuotaPolicyExcelApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户配额策略（tenant_quota_policy）配置的 Excel 导出与空白模板下载。
 *
 * <p>权限：导出含只读审计角色。配额策略由建租户时从 {@code default} 模板自动初始化，后续调整请通过页面单条维护。
 */
@RestController
@Validated
@RequestMapping("/api/console/config/quota-policies/excel")
@RequiredArgsConstructor
public class ConsoleTenantQuotaPolicyExcelController {

  private final ConsoleTenantQuotaPolicyExcelApplicationService applicationService;

  /** 按查询条件导出当前租户可见的配额策略配置为 {@code .xlsx} 流。 */
  @GetMapping("/export")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public ResponseEntity<InputStreamResource> export(
      @RequestParam(required = false) String tenantId,
      @RequestParam(required = false) String policyCode,
      @RequestParam(required = false) Boolean enabled) {
    return applicationService.exportQuotaPolicies(tenantId, policyCode, enabled);
  }

  /** 下载空白模板。 */
  @GetMapping("/template")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public ResponseEntity<InputStreamResource> template() {
    return applicationService.downloadTemplate();
  }
}
