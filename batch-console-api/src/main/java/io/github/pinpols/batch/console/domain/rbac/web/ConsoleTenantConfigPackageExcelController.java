package io.github.pinpols.batch.console.domain.rbac.web;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.application.config.TenantConfigPackageExcelService;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigPackageExcelApplyRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigPackageExcelPatchRequest;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigPackageExcelApplyResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigPackageExcelGuideResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigPackageExcelPreviewResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigPackageExcelUploadResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleExcelDownloadResponse;
import io.github.pinpols.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 租户配置包（tenant-config-package）多 Sheet Excel 批量导入接口。
 *
 * <p>完整配置包包含 12 个数据 Sheet；场景化模板只输出所选业务 Sheet 和列。
 *
 * <p>典型流程：{@code GET /scenario-template} 下载所选场景模板 → {@code POST /upload} 上传得 {@code uploadToken} → {@code GET
 * /preview/{uploadToken}} 预览校验 → {@code GET /preview/{uploadToken}/workbook} 下载带批注 workbook（可选）→
 * {@code POST /apply/{uploadToken}} 全量事务写库。
 */
@RestController
@Validated
@RequestMapping("/api/console/config/tenant-package/excel")
@RequiredArgsConstructor
@Idempotent
public class ConsoleTenantConfigPackageExcelController {

  private final TenantConfigPackageExcelService tenantConfigExcelService;
  private final ConsoleResponseFactory responseFactory;

  /**
   * 导出当前租户完整配置包，可直接回灌至 {@code /upload → /apply} 流程。
   *
   * <p>R2-P1-9：返回 {@link StreamingResponseBody} 让 workbook 直接写到响应流，不再缓 byte[]。
   */
  @GetMapping("/export")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN_OR_AUDITOR)
  public ResponseEntity<StreamingResponseBody> export(
      @RequestParam(required = false) String tenantId) {
    return ConsoleExcelDownloadResponse.from(tenantConfigExcelService.exportPackage(tenantId));
  }

  /**
   * 下载包含全部配置 Sheet 及填写说明的兼容型空白导入模板 {@code .xlsx}。
   *
   * <p>R2-P1-9：streaming 返回避免 byte[] 双拷贝。
   */
  @GetMapping("/template")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN_OR_AUDITOR)
  public ResponseEntity<StreamingResponseBody> template() {
    return ConsoleExcelDownloadResponse.from(tenantConfigExcelService.downloadTemplate());
  }

  /** 下载按场景投影的空白模板；ALL 表示全部租户业务场景，不包含平台资源配置。 */
  @GetMapping("/scenario-template")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN_OR_AUDITOR)
  public ResponseEntity<StreamingResponseBody> scenarioTemplate(
      @RequestParam("scenarios") List<String> scenarios) {
    return ConsoleExcelDownloadResponse.from(
        tenantConfigExcelService.downloadScenarioTemplate(scenarios));
  }

  /** 下载带示例数据的场景化配置包模板。 */
  @GetMapping("/sample-template")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN_OR_AUDITOR)
  public ResponseEntity<StreamingResponseBody> sampleTemplate(
      @RequestParam(value = "scenario", required = false) String scenario,
      @RequestParam(value = "scenarios", required = false) List<String> scenarios) {
    return ConsoleExcelDownloadResponse.from(
        tenantConfigExcelService.downloadSampleTemplate(scenario, scenarios));
  }

  /** 返回配置 Sheet 的必填、类型、枚举、默认留空行为和填写示例，供 Console 页面直接展示。 */
  @GetMapping("/guide")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN_OR_AUDITOR)
  public CommonResponse<TenantConfigPackageExcelGuideResponse> guide() {
    return responseFactory.success(tenantConfigExcelService.guide());
  }

  /**
   * 上传租户配置包 Excel，解析已识别的 Sheet 后写入服务端临时会话，返回 {@code uploadToken}。
   *
   * @param file 表单字段名 {@code file}，内容为 xlsx
   * @param tenantId 目标租户 id。ROLE_ADMIN / ROLE_TENANT_ADMIN 是全局角色，必须显式指定； 租户级账号可不传（自动沿用 JWT 内
   *     tenantId）
   */
  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN)
  public CommonResponse<TenantConfigPackageExcelUploadResponse> upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "tenantId", required = false) String tenantId)
      throws IOException {
    return responseFactory.success(tenantConfigExcelService.upload(file, tenantId));
  }

  /**
   * 对已上传的配置包执行跨 Sheet 依赖校验，返回各 Sheet 行统计与问题列表，不写库。
   *
   * @param uploadToken {@code /upload} 响应中的令牌
   */
  @GetMapping("/preview/{uploadToken}")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN)
  public CommonResponse<TenantConfigPackageExcelPreviewResponse> preview(
      @PathVariable String uploadToken) {
    return responseFactory.success(tenantConfigExcelService.preview(uploadToken));
  }

  /**
   * 预览出错行内联编辑:把改动的单元格回写到上传会话并重校验,返回新预览,免去“下 Excel→改→重传”。 不落库——仍需走 {@code /apply}。
   *
   * @param uploadToken 与预览阶段相同
   * @param request 定位 (sheetName, rowNo) + 改动单元格 values
   */
  @PostMapping("/preview/{uploadToken}/patch")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN)
  public CommonResponse<TenantConfigPackageExcelPreviewResponse> patchPreviewRow(
      @PathVariable String uploadToken,
      @Valid @RequestBody TenantConfigPackageExcelPatchRequest request) {
    TenantConfigPackageExcelPreviewResponse preview = tenantConfigExcelService.patchRow(
        uploadToken,
        request.getPreviewToken(),
        request.getSheetName(),
        request.getRowNo(),
        request.getValues());
    return responseFactory.success(preview);
  }

  /**
   * 下载带校验批注的预览 workbook；有校验问题时可修正后重新上传。
   *
   * @param uploadToken 与预览阶段相同
   */
  @GetMapping("/preview/{uploadToken}/workbook")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN)
  public ResponseEntity<StreamingResponseBody> previewWorkbook(@PathVariable String uploadToken) {
    return ConsoleExcelDownloadResponse.from(
        tenantConfigExcelService.downloadPreviewWorkbook(uploadToken));
  }

  /**
   * 将已通过预览的配置包数据在单一事务内全量写入/更新，并记录配置变更。
   * 租户管理员必须提交预览令牌；平台管理员保留旧客户端的无令牌调用方式。
   *
   * @param idempotencyKey 请求头幂等键，防重复提交
   * @param uploadToken 与预览阶段相同
   * @param request 可选变更说明（见 {@link TenantConfigPackageExcelApplyRequest}）
   */
  @PostMapping("/apply/{uploadToken}")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN_WITH_PREVIEW_TOKEN)
  public CommonResponse<TenantConfigPackageExcelApplyResponse> apply(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable String uploadToken,
      @Valid @RequestBody TenantConfigPackageExcelApplyRequest request) {
    return responseFactory.success(tenantConfigExcelService.apply(uploadToken, request));
  }
}
