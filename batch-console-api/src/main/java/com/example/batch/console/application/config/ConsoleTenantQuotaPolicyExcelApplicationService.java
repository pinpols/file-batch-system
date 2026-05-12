package com.example.batch.console.application.config;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;

/** 租户配额策略 Excel 模板与导出应用服务。 */
public interface ConsoleTenantQuotaPolicyExcelApplicationService {

  /** 导出租户配额策略配置为 Excel。 */
  ResponseEntity<InputStreamResource> exportQuotaPolicies(
      String tenantId, String policyCode, Boolean enabled);

  /** 下载空白模板。 */
  ResponseEntity<InputStreamResource> downloadTemplate();
}
