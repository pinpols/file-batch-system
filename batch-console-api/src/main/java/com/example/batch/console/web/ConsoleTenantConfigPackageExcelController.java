package com.example.batch.console.web;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.config.ConsoleTenantConfigPackageExcelApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.Idempotent;
import com.example.batch.console.web.request.config.TenantConfigPackageExcelApplyRequest;
import com.example.batch.console.web.response.config.TenantConfigPackageExcelApplyResponse;
import com.example.batch.console.web.response.config.TenantConfigPackageExcelPreviewResponse;
import com.example.batch.console.web.response.config.TenantConfigPackageExcelUploadResponse;
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
 * 租户配置包（tenant-config-package）多 Sheet Excel 批量导入接口。
 *
 * <p>8 个数据 Sheet 合并为单文件导入：job_definition / file_channel_config / file_template_config /
 * pipeline_definition / pipeline_step_definition / workflow_definition / workflow_node /
 * workflow_edge。
 *
 * <p>典型流程：{@code GET /template} 下载模板 → {@code POST /upload} 上传得 {@code uploadToken} → {@code GET
 * /preview/{uploadToken}} 预览校验 → {@code GET /preview/{uploadToken}/workbook} 下载带批注 workbook（可选）→
 * {@code POST /apply/{uploadToken}} 全量事务写库。
 */
@RestController
@Validated
@RequestMapping("/api/console/config/tenant-package/excel")
@RequiredArgsConstructor
@Idempotent
public class ConsoleTenantConfigPackageExcelController {

  private final ConsoleTenantConfigPackageExcelApplicationService applicationService;
  private final ConsoleResponseFactory responseFactory;

  /** 导出当前租户全量配置包（8 Sheet），可直接回灌至 {@code /upload → /apply} 流程。 */
  @GetMapping("/export")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public ResponseEntity<InputStreamResource> export(
      @RequestParam(required = false) String tenantId) {
    return applicationService.exportPackage(tenantId);
  }

  /** 下载包含全部 8 个数据 Sheet 及填写说明的空白导入模板 {@code .xlsx}。 */
  @GetMapping("/template")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public ResponseEntity<InputStreamResource> template() {
    return applicationService.downloadTemplate();
  }

  /**
   * 上传租户配置包 Excel，解析 8 个 Sheet 后写入服务端临时会话，返回 {@code uploadToken}。
   *
   * @param file 表单字段名 {@code file}，内容为 xlsx
   * @param tenantId 目标租户 id。ROLE_ADMIN / ROLE_CONFIG_ADMIN 是全局角色，必须显式指定； 租户级账号可不传（自动沿用 JWT 内
   *     tenantId）
   */
  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
  public CommonResponse<TenantConfigPackageExcelUploadResponse> upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "tenantId", required = false) String tenantId)
      throws IOException {
    return responseFactory.success(applicationService.upload(file, tenantId));
  }

  /**
   * 对已上传的配置包执行跨 Sheet 依赖校验，返回各 Sheet 行统计与问题列表，不写库。
   *
   * @param uploadToken {@code /upload} 响应中的令牌
   */
  @GetMapping("/preview/{uploadToken}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
  public CommonResponse<TenantConfigPackageExcelPreviewResponse> preview(
      @PathVariable String uploadToken) {
    return responseFactory.success(applicationService.preview(uploadToken));
  }

  /**
   * 下载带校验批注的预览 workbook；有校验问题时可修正后重新上传。
   *
   * @param uploadToken 与预览阶段相同
   */
  @GetMapping("/preview/{uploadToken}/workbook")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
  public ResponseEntity<InputStreamResource> previewWorkbook(@PathVariable String uploadToken) {
    return applicationService.downloadPreviewWorkbook(uploadToken);
  }

  /**
   * 将已通过预览的配置包数据在单一事务内全量写入/更新，并记录配置变更。
   *
   * @param idempotencyKey 请求头幂等键，防重复提交
   * @param uploadToken 与预览阶段相同
   * @param request 可选变更说明（见 {@link TenantConfigPackageExcelApplyRequest}）
   */
  @PostMapping("/apply/{uploadToken}")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<TenantConfigPackageExcelApplyResponse> apply(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable String uploadToken,
      @Valid @RequestBody TenantConfigPackageExcelApplyRequest request) {
    return responseFactory.success(applicationService.apply(uploadToken, request));
  }
}
