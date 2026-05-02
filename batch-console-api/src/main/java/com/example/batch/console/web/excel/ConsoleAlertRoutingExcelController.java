package com.example.batch.console.web.excel;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ConsoleAlertRoutingExcelApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.query.AlertRoutingQueryRequest;
import com.example.batch.console.web.response.ExcelQuickImportResponse;
import com.example.batch.console.web.response.config.ConsoleAlertRoutingResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 告警路由配置 Excel 导入导出 REST:仅保留 export / template / quick-import 三条。
 *
 * <p>历史 upload / preview / preview-workbook / apply 4 个端点已于 2026-05-01 物删 (合并到 {@code
 * /api/console/config/tenant-package/excel} 一站式接口)。
 */
@RestController
@Validated
@RequestMapping("/api/console/config/alert-routings/excel")
@RequiredArgsConstructor
public class ConsoleAlertRoutingExcelController {

  private final ConsoleAlertRoutingExcelApplicationService applicationService;
  private final ConsoleResponseFactory responseFactory;

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

  /** 一键导入：上传 + 校验 + 应用合并为一次调用。无错误自动 apply，有错误返回 preview 和错误 workbook URL。 */
  @PostMapping(value = "/quick-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<ExcelQuickImportResponse<ConsoleAlertRoutingResponse>> quickImport(
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "reason", required = false) String reason,
      @RequestParam(value = "skipInvalid", defaultValue = "false") boolean skipInvalid)
      throws IOException {
    return responseFactory.success(applicationService.quickImport(file, reason, skipInvalid));
  }
}
