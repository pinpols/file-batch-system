package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.model.PageResponse;
import com.example.batch.console.application.ConsoleFileTemplateApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.query.FileTemplateQueryRequest;
import com.example.batch.console.web.request.FileTemplateCreateRequest;
import com.example.batch.console.web.request.FileTemplateUpdateRequest;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 文件模板（file_template_config）CRUD REST 接口。 */
@RestController
@Validated
@RequestMapping("/api/console/file-templates")
@RequiredArgsConstructor
public class ConsoleFileTemplateController {

    private final ConsoleFileTemplateApplicationService fileTemplateApplicationService;
    private final ConsoleResponseFactory responseFactory;

    /** 分页查询文件模板列表。 */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
    public CommonResponse<PageResponse<Map<String, Object>>> list(
            @Valid @ModelAttribute FileTemplateQueryRequest request) {
        return responseFactory.success(fileTemplateApplicationService.list(request));
    }

    /** 获取文件模板详情。 */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
    public CommonResponse<Map<String, Object>> get(
            @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
        return responseFactory.success(fileTemplateApplicationService.get(id, tenantId));
    }

    /** 新建文件模板。 */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
    public CommonResponse<Map<String, Object>> create(
            @Valid @RequestBody FileTemplateCreateRequest request) {
        return responseFactory.success(fileTemplateApplicationService.create(request));
    }

    /** 更新文件模板。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
    public CommonResponse<Map<String, Object>> update(
            @PathVariable Long id, @Valid @RequestBody FileTemplateUpdateRequest request) {
        return responseFactory.success(fileTemplateApplicationService.update(id, request));
    }

    /** 删除文件模板。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public CommonResponse<Void> delete(
            @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
        fileTemplateApplicationService.delete(id, tenantId);
        return responseFactory.success(null);
    }

    /** 启用/禁用文件模板。 */
    @PostMapping("/{id}/toggle")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
    public CommonResponse<Void> toggle(
            @PathVariable Long id,
            @RequestParam("tenantId") String tenantId,
            @RequestParam("enabled") Boolean enabled) {
        fileTemplateApplicationService.toggle(id, tenantId, enabled);
        return responseFactory.success(null);
    }
}
