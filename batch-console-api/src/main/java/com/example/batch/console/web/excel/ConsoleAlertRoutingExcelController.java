package com.example.batch.console.web.excel;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ConsoleAlertRoutingExcelApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.ConsoleTenantConfigPackageExcelController;
import com.example.batch.console.web.query.AlertRoutingQueryRequest;
import com.example.batch.console.web.request.ExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleAlertRoutingResponse;
import com.example.batch.console.web.response.ExcelApplyResponse;
import com.example.batch.console.web.response.ExcelPreviewResponse;
import com.example.batch.console.web.response.ExcelQuickImportResponse;
import com.example.batch.console.web.response.ExcelUploadResponse;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 告警路由配置 Excel 导入导出 REST：导出、模板下载、上传、预览、确认落库。
 *
 * @deprecated upload / preview / previewWorkbook / apply 已由 {@link
 *     ConsoleTenantConfigPackageExcelController} 合并导入替代， 请改用 {@code
 *     /api/console/config/tenant-package/excel} 系列接口；export 仍可用。
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

  /**
   * @deprecated 已废弃，请改用 {@link ConsoleTenantConfigPackageExcelController} 合并导入接口。
   */
  @Deprecated
  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
  public CommonResponse<ExcelUploadResponse> upload(@RequestParam("file") MultipartFile file)
      throws IOException {
    return responseFactory.success(applicationService.upload(file));
  }

  /**
   * @deprecated 已废弃，请改用 {@link ConsoleTenantConfigPackageExcelController} 合并导入接口。
   */
  @Deprecated
  @GetMapping("/preview/{uploadToken}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
  public CommonResponse<ExcelPreviewResponse<ConsoleAlertRoutingResponse>> preview(
      @PathVariable String uploadToken) {
    return responseFactory.success(applicationService.preview(uploadToken));
  }

  /**
   * @deprecated 已废弃，请改用 {@link ConsoleTenantConfigPackageExcelController} 合并导入接口。
   */
  @Deprecated
  @GetMapping("/preview/{uploadToken}/workbook")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
  public ResponseEntity<InputStreamResource> previewWorkbook(@PathVariable String uploadToken) {
    return applicationService.downloadPreviewWorkbook(uploadToken);
  }

  /**
   * @deprecated 已废弃，请改用 {@link ConsoleTenantConfigPackageExcelController} 合并导入接口。
   */
  @Deprecated
  @PostMapping("/apply/{uploadToken}")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<ExcelApplyResponse> apply(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable String uploadToken,
      @Valid @RequestBody ExcelApplyRequest request) {
    return responseFactory.success(applicationService.apply(uploadToken, request));
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
