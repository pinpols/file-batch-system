package com.example.batch.console.web;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ConsoleTenantQuotaPolicyExcelApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.request.TenantQuotaPolicyExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleTenantQuotaPolicyExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleTenantQuotaPolicyExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleTenantQuotaPolicyExcelUploadResponse;
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
 * 租户配额策略（tenant_quota_policy）配置的 Excel 批量维护接口。
 *
 * <p>典型流程：{@code GET /export} 导出 → {@code POST /upload} 上传得 {@code uploadToken} →
 * {@code GET /preview/{uploadToken}} 校验预览 → {@code POST /apply/{uploadToken}} 确认写库（需幂等键）。
 *
 * <p>权限：导出含只读审计角色；上传/预览为配置管理员；落库仅管理员。
 */
@RestController
@Validated
@RequestMapping("/api/console/config/quota-policies/excel")
@RequiredArgsConstructor
public class ConsoleTenantQuotaPolicyExcelController {

    private final ConsoleTenantQuotaPolicyExcelApplicationService applicationService;
    private final ConsoleResponseFactory responseFactory;

    /**
     * 按查询条件导出当前租户可见的配额策略配置为 {@code .xlsx} 流。
     */
    @GetMapping("/export")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
    public ResponseEntity<InputStreamResource> export(@RequestParam(required = false) String tenantId,
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

    /**
     * 上传 Excel 工作簿，解析后写入服务端临时会话，返回 {@code uploadToken} 供预览与确认。
     *
     * @param file 表单字段名 {@code file}，内容为 xlsx
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
    public CommonResponse<ConsoleTenantQuotaPolicyExcelUploadResponse> upload(@RequestParam("file") MultipartFile file) throws IOException {
        return responseFactory.success(applicationService.upload(file));
    }

    /**
     * 根据 {@code uploadToken} 返回解析后的行数据及校验问题，不写库。
     *
     * @param uploadToken {@code /upload} 响应中的令牌
     */
    @GetMapping("/preview/{uploadToken}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
    public CommonResponse<ConsoleTenantQuotaPolicyExcelPreviewResponse> preview(@PathVariable String uploadToken) {
        return responseFactory.success(applicationService.preview(uploadToken));
    }

    /** 下载带校验问题与批注的预览 workbook。 */
    @GetMapping("/preview/{uploadToken}/workbook")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
    public ResponseEntity<InputStreamResource> previewWorkbook(@PathVariable String uploadToken) {
        return applicationService.downloadPreviewWorkbook(uploadToken);
    }

    /**
     * 将已通过预览的会话数据批量写入/更新配额策略配置，并记录配置变更。
     *
     * @param idempotencyKey 请求头幂等键，防重复提交
     * @param uploadToken    与预览阶段相同
     * @param request        可选说明，如落库原因（见 {@link TenantQuotaPolicyExcelApplyRequest}）
     */
    @PostMapping("/apply/{uploadToken}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public CommonResponse<ConsoleTenantQuotaPolicyExcelApplyResponse> apply(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                                                            @PathVariable String uploadToken,
                                                                            @Valid @RequestBody TenantQuotaPolicyExcelApplyRequest request) {
        return responseFactory.success(applicationService.apply(uploadToken, request));
    }
}
