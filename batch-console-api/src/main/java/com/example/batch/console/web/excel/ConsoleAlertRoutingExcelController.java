package com.example.batch.console.web.excel;

import com.example.batch.console.application.monitor.ConsoleAlertRoutingExcelApplicationService;
import com.example.batch.console.web.query.AlertRoutingQueryRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 告警路由配置 Excel 导出 REST:仅保留 export / template。
 *
 * <p>历史 upload / preview / preview-workbook / apply / quick-import 端点已物删 (合并到 {@code
 * /api/console/config/tenant-package/excel} 一站式接口)。
 */
@RestController
@Validated
@RequestMapping("/api/console/config/alert-routings/excel")
@RequiredArgsConstructor
public class ConsoleAlertRoutingExcelController {

  private final ConsoleAlertRoutingExcelApplicationService applicationService;

  /** 导出告警路由配置 Excel。 */
  @GetMapping("/export")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public ResponseEntity<InputStreamResource> export(
      @Valid @ModelAttribute AlertRoutingQueryRequest request) {
    return applicationService.exportAlertRoutings(request);
  }

  /** 下载空白模板。 */
  @GetMapping("/template")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public ResponseEntity<InputStreamResource> template() {
    return applicationService.downloadTemplate();
  }
}
