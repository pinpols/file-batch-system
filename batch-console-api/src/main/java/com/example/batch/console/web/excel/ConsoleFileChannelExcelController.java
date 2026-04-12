package com.example.batch.console.web.excel;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ConsoleFileChannelExcelApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.ConsoleTenantConfigPackageExcelController;
import com.example.batch.console.web.query.FileChannelQueryRequest;
import com.example.batch.console.web.request.FileChannelExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleFileChannelExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleFileChannelExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleFileChannelExcelUploadResponse;
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
 * 文件通道（file_channel）配置的 Excel 批量维护接口。
 *
 * <p>典型流程：{@code GET /export} 导出 → {@code POST /upload} 上传得 {@code uploadToken} → {@code GET
 * /preview/{uploadToken}} 校验预览 → {@code POST /apply/{uploadToken}} 确认写库（需幂等键）。
 *
 * <p>权限：导出含只读审计角色；上传/预览为配置管理员；落库仅管理员。
 *
 * @deprecated upload / preview / previewWorkbook / apply 已由 {@link
 *     ConsoleTenantConfigPackageExcelController} 合并导入替代， 请改用 {@code
 *     /api/console/config/tenant-package/excel} 系列接口；export 仍可用。
 */
@RestController
@Validated
@RequestMapping("/api/console/config/file-channels/excel")
@RequiredArgsConstructor
public class ConsoleFileChannelExcelController {

  private final ConsoleFileChannelExcelApplicationService applicationService;
  private final ConsoleResponseFactory responseFactory;

  /**
   * 按查询条件导出当前租户可见的文件通道配置为 {@code .xlsx} 流。
   *
   * @param request 筛选条件（租户等，与列表查询一致）
   */
  @GetMapping("/export")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public ResponseEntity<InputStreamResource> export(
      @Valid @ModelAttribute FileChannelQueryRequest request) {
    return applicationService.exportFileChannels(request);
  }

  /** 下载空白模板。 */
  @GetMapping("/template")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public ResponseEntity<InputStreamResource> template() {
    return applicationService.downloadTemplate();
  }

  /**
   * @deprecated 已废弃，请改用 {@link ConsoleTenantConfigPackageExcelController} 合并导入接口。
   * @param file 表单字段名 {@code file}，内容为 xlsx
   */
  @Deprecated
  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
  public CommonResponse<ConsoleFileChannelExcelUploadResponse> upload(
      @RequestParam("file") MultipartFile file) throws IOException {
    return responseFactory.success(applicationService.upload(file));
  }

  /**
   * @deprecated 已废弃，请改用 {@link ConsoleTenantConfigPackageExcelController} 合并导入接口。
   * @param uploadToken {@code /upload} 响应中的令牌
   */
  @Deprecated
  @GetMapping("/preview/{uploadToken}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
  public CommonResponse<ConsoleFileChannelExcelPreviewResponse> preview(
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
  public CommonResponse<ConsoleFileChannelExcelApplyResponse> apply(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable String uploadToken,
      @Valid @RequestBody FileChannelExcelApplyRequest request) {
    return responseFactory.success(applicationService.apply(uploadToken, request));
  }
}
