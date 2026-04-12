package com.example.batch.console.web.excel;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ConsoleFileTemplateExcelApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.query.FileTemplateQueryRequest;
import com.example.batch.console.web.request.FileTemplateExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleFileTemplateExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleFileTemplateExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleFileTemplateExcelUploadResponse;

import jakarta.validation.Valid;

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

import java.io.IOException;

/**
 * 文件模板 Excel 导入导出 REST。
 *
 * @deprecated upload / preview / previewWorkbook / apply 已废弃；文件模板由建租户时从 {@code default}
 *     模板自动初始化，后续调整请通过页面单条维护。export 仍可用。
 */
@RestController
@Validated
@RequestMapping("/api/console/config/file-templates/excel")
@RequiredArgsConstructor
public class ConsoleFileTemplateExcelController {

    private final ConsoleFileTemplateExcelApplicationService applicationService;
    private final ConsoleResponseFactory responseFactory;

    /** 导出文件模板 Excel。 */
    @GetMapping("/export")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
    public ResponseEntity<InputStreamResource> export(
            @Valid @ModelAttribute FileTemplateQueryRequest request) {
        return applicationService.exportFileTemplates(request);
    }

    /** 下载空白模板。 */
    @GetMapping("/template")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
    public ResponseEntity<InputStreamResource> template() {
        return applicationService.downloadTemplate();
    }

    /** @deprecated 已废弃；文件模板由建租户时从 {@code default} 模板自动初始化，后续调整请通过页面单条维护。 */
    @Deprecated
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
    public CommonResponse<ConsoleFileTemplateExcelUploadResponse> upload(
            @RequestParam("file") MultipartFile file) throws IOException {
        return responseFactory.success(applicationService.upload(file));
    }

    /** @deprecated 已废弃；文件模板由建租户时从 {@code default} 模板自动初始化，后续调整请通过页面单条维护。 */
    @Deprecated
    @GetMapping("/preview/{uploadToken}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
    public CommonResponse<ConsoleFileTemplateExcelPreviewResponse> preview(
            @PathVariable String uploadToken) {
        return responseFactory.success(applicationService.preview(uploadToken));
    }

    /** @deprecated 已废弃；文件模板由建租户时从 {@code default} 模板自动初始化，后续调整请通过页面单条维护。 */
    @Deprecated
    @GetMapping("/preview/{uploadToken}/workbook")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
    public ResponseEntity<InputStreamResource> previewWorkbook(@PathVariable String uploadToken) {
        return applicationService.downloadPreviewWorkbook(uploadToken);
    }

    /** @deprecated 已废弃；文件模板由建租户时从 {@code default} 模板自动初始化，后续调整请通过页面单条维护。 */
    @Deprecated
    @PostMapping("/apply/{uploadToken}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public CommonResponse<ConsoleFileTemplateExcelApplyResponse> apply(
            @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @PathVariable String uploadToken,
            @Valid @RequestBody FileTemplateExcelApplyRequest request) {
        return responseFactory.success(applicationService.apply(uploadToken, request));
    }
}
