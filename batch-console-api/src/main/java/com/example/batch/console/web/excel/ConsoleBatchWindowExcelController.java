package com.example.batch.console.web.excel;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ConsoleBatchWindowExcelApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.request.ExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleBatchWindowResponse;
import com.example.batch.console.web.response.ExcelApplyResponse;
import com.example.batch.console.web.response.ExcelPreviewResponse;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 批处理窗口（batch_window）配置的 Excel 批量维护接口。
 *
 * <p>典型流程：{@code GET /export} 导出 → {@code POST /upload} 上传得 {@code uploadToken} → {@code GET
 * /preview/{uploadToken}} 校验预览 → {@code POST /apply/{uploadToken}} 确认写库（需幂等键）。
 *
 * <p>权限：导出含只读审计角色；上传/预览为配置管理员；落库仅管理员。
 *
 * @deprecated upload / preview / previewWorkbook / apply 已废弃；批处理窗口由建租户时从 {@code default}
 *     模板自动初始化，后续调整请通过页面单条维护。export 仍可用。
 */
@RestController
@Validated
@RequestMapping("/api/console/config/batch-windows/excel")
@RequiredArgsConstructor
public class ConsoleBatchWindowExcelController {

  private final ConsoleBatchWindowExcelApplicationService applicationService;
  private final ConsoleResponseFactory responseFactory;

  /**
   * 按查询条件导出当前租户可见的批处理窗口配置为 {@code .xlsx} 流。
   *
   * @param tenantId 可选租户 ID
   */
  @GetMapping("/export")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public ResponseEntity<InputStreamResource> export(
      @RequestParam(required = false) String tenantId) {
    return applicationService.exportBatchWindows(tenantId);
  }

  /** 下载空白模板。 */
  @GetMapping("/template")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public ResponseEntity<InputStreamResource> template() {
    return applicationService.downloadTemplate();
  }

  /**
   * @deprecated 已废弃；批处理窗口由建租户时从 {@code default} 模板自动初始化，后续调整请通过页面单条维护。
   * @param file 表单字段名 {@code file}，内容为 xlsx
   */
  @Deprecated
  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
  public CommonResponse<ExcelUploadResponse> upload(@RequestParam("file") MultipartFile file)
      throws IOException {
    return responseFactory.success(applicationService.upload(file));
  }

  /**
   * @deprecated 已废弃；批处理窗口由建租户时从 {@code default} 模板自动初始化，后续调整请通过页面单条维护。
   * @param uploadToken {@code /upload} 响应中的令牌
   */
  @Deprecated
  @GetMapping("/preview/{uploadToken}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
  public CommonResponse<ExcelPreviewResponse<ConsoleBatchWindowResponse>> preview(
      @PathVariable String uploadToken) {
    return responseFactory.success(applicationService.preview(uploadToken));
  }

  /**
   * @deprecated 已废弃；批处理窗口由建租户时从 {@code default} 模板自动初始化，后续调整请通过页面单条维护。
   */
  @Deprecated
  @GetMapping("/preview/{uploadToken}/workbook")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
  public ResponseEntity<InputStreamResource> previewWorkbook(@PathVariable String uploadToken) {
    return applicationService.downloadPreviewWorkbook(uploadToken);
  }

  /**
   * @deprecated 已废弃；批处理窗口由建租户时从 {@code default} 模板自动初始化，后续调整请通过页面单条维护。
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
}
